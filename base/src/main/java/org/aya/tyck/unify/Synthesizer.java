// Copyright (c) 2020-2023 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.tyck.unify;

import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.MutableList;
import org.aya.core.def.Def;
import org.aya.core.def.PrimDef;
import org.aya.core.meta.MetaInfo;
import org.aya.core.term.*;
import org.aya.core.visitor.DeltaExpander;
import org.aya.core.visitor.Subst;
import org.aya.generic.SortKind;
import org.aya.generic.util.InternalException;
import org.aya.generic.util.NormalizeMode;
import org.aya.guest0x0.cubical.Partial;
import org.aya.prettier.AyaPrettierOptions;
import org.aya.tyck.env.LocalCtx;
import org.aya.tyck.tycker.TyckState;
import org.aya.util.Arg;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Similar to <code>GetTypeVisitor</code> in Arend.
 *
 * @author ice1000
 * @see org.aya.tyck.unify.DoubleChecker
 */
public record Synthesizer(@NotNull TyckState state, @NotNull LocalCtx ctx) {
  public @NotNull Term press(@NotNull Term preterm) {
    var synthesize = synthesize(preterm);
    assert synthesize != null : preterm.toDoc(AyaPrettierOptions.pretty()).debugRender();
    return whnf(synthesize);
  }

  public boolean inheritPiDom(Term type, SortTerm expected) {
    if (type instanceof MetaTerm meta && meta.ref().info instanceof MetaInfo.AnyType) {
      var typed = meta.asPiDom(expected);
      state.solve(meta.ref(), typed);
      return false;
    }
    if (!(synthesize(type) instanceof SortTerm actual)) return false;
    return switch (expected.kind()) {
      case Prop -> switch (actual.kind()) {
        case Prop, Type -> true;
        case Set, ISet -> false;
      };
      case Type -> actual.kind() != SortKind.Set && actual.lift() <= expected.lift();
      case Set -> actual.lift() <= expected.lift();
      case ISet -> unreachable(type);
    };
  }

  public @Nullable Term synthesize(@NotNull Term preterm) {
    return switch (preterm) {
      case RefTerm term -> ctx.get(term.var());
      case ConCall conCall -> conCall.head().underlyingDataCall();
      case Callable.DefCall call -> Def.defResult(call.ref())
        .subst(DeltaExpander.buildSubst(Def.defTele(call.ref()), call.args()))
        .lift(call.ulift());
      // TODO[isType]: deal with type-only metas
      case MetaTerm hole -> {
        var info = hole.ref().info;
        if (info instanceof MetaInfo.Result(var result)) yield result;
        var simpl = whnf(hole);
        if (simpl instanceof MetaTerm again) {
          throw new UnsupportedOperationException("TODO");
        } else yield synthesize(simpl);
      }
      case RefTerm.Field field -> Def.defType(field.ref());
      case FieldTerm access -> {
        var callRaw = press(access.of());
        if (!(callRaw instanceof StructCall call)) yield unreachable(callRaw);
        var field = access.ref();
        var subst = DeltaExpander.buildSubst(Def.defTele(field), access.fieldArgs())
          .add(DeltaExpander.buildSubst(Def.defTele(call.ref()), access.structArgs()));
        yield Def.defResult(field).subst(subst);
      }
      case SigmaTerm sigma -> {
        var univ = MutableList.<SortTerm>create();
        for (var param : sigma.params()) {
          var pressed = synthesize(param.type());
          if (!(pressed instanceof SortTerm sort)) yield null;
          univ.append(sort);
          ctx.put(param);
        }
        ctx.remove(sigma.params().view().map(Term.Param::ref));
        yield univ.reduce(SigmaTerm::max);
      }
      case PiTerm pi -> {
        var paramTyRaw = synthesize(pi.param().type());
        if (!(paramTyRaw instanceof SortTerm paramTy)) yield null;
        var t = new Synthesizer(state, ctx.deriveSeq());
        yield t.ctx.with(pi.param(), () -> {
          if (t.press(pi.body()) instanceof SortTerm retTy) {
            return PiTerm.max(paramTy, retTy);
          } else return null;
        });
      }
      case NewTerm neu -> neu.struct();
      case ErrorTerm term -> ErrorTerm.typeOf(term.description());
      case ProjTerm proj -> {
        var sigmaRaw = synthesize(proj.of());
        if (!(sigmaRaw instanceof SigmaTerm sigma)) yield null;
        var index = proj.ix() - 1;
        var telescope = sigma.params();
        yield telescope.get(index).type().subst(ProjTerm.projSubst(proj.of(), index, telescope));
      }
      case MetaPatTerm metaPat -> metaPat.ref().type();
      case MetaLitTerm lit -> lit.type();
      case SortTerm sort -> sort.succ();
      case IntervalTerm interval -> SortTerm.ISet;
      case FormulaTerm end -> IntervalTerm.INSTANCE;
      case StringTerm str -> state.primFactory().getCall(PrimDef.ID.STRING);
      case IntegerTerm shaped -> shaped.type();
      case ListTerm shaped -> shaped.type();
      case PartialTyTerm ty -> synthesize(ty.type());
      case PartialTerm(var rhs, var par) -> new PartialTyTerm(par, rhs.restr());
      case PathTerm cube -> synthesize(cube.type());
      case MatchTerm match -> {
        // TODO: Should I normalize match.discriminant() before matching?
        var term = match.tryMatch();
        yield term.isDefined() ? synthesize(term.get()) : ErrorTerm.typeOf(match);
      }
      case CoeTerm coe -> PrimDef.familyLeftToRight(coe.type());
      case HCompTerm hComp -> throw new InternalException("TODO");
      case InTerm(var phi, var u) -> {
        var ty = press(u);
        yield state.primFactory().getCall(PrimDef.ID.SUB, ImmutableSeq.of(
            ty, phi, PartialTerm.from(phi, u, ty))
          .map(t -> new Arg<>(t, true)));
      }
      case OutTerm outS -> {
        var ty = press(outS.of());
        if (ty instanceof PrimCall sub) yield sub.args().first().term();
        yield unreachable(outS);
      }
      case PAppTerm app -> {
        // v @ ui : A[ui/xi]
        var xi = app.cube().params();
        var ui = app.args().map(Arg::term);
        yield app.cube().type().subst(new Subst(xi, ui));
      }
      case PLamTerm lam -> {
        var bud = press(lam.body());
        yield new PathTerm(lam.params(), bud, new Partial.Const<>(bud));
      }
      case AppTerm app -> {
        var piRaw = press(app.of());
        yield piRaw instanceof PiTerm pi ? pi.substBody(app.arg().term()) : null;
      }
      case default -> null;
    };
  }

  static <T> T unreachable(@NotNull Term preterm) {
    throw new AssertionError("Unexpected term: " + preterm);
  }

  private @NotNull Term whnf(Term x) {
    return x.normalize(state, NormalizeMode.WHNF);
  }
}
