// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.resolve.visitor;

import kala.collection.SeqLike;
import kala.collection.SeqView;
import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.*;
import kala.tuple.Tuple;
import kala.tuple.Tuple2;
import org.aya.concrete.Expr;
import org.aya.concrete.Pattern;
import org.aya.concrete.stmt.GeneralizedVar;
import org.aya.generic.util.InternalException;
import org.aya.ref.AnyVar;
import org.aya.ref.DefVar;
import org.aya.ref.LocalVar;
import org.aya.resolve.context.Context;
import org.aya.resolve.error.GeneralizedNotAvailableError;
import org.aya.tyck.error.FieldError;
import org.aya.tyck.order.TyckOrder;
import org.aya.tyck.order.TyckUnit;
import org.aya.util.error.SourcePos;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Resolves bindings.
 *
 * @param allowedGeneralizes will be filled with generalized vars if allowGeneralized,
 *                           and represents the allowed generalized level vars otherwise
 * @author re-xyr, ice1000
 * @implSpec allowedGeneralizes must be linked map
 * @see StmtResolver
 */
public record ExprResolver(
  @NotNull Options options,
  @NotNull MutableMap<GeneralizedVar, Expr.Param> allowedGeneralizes,
  @NotNull MutableList<TyckOrder> reference,
  @NotNull MutableStack<Where> where,
  @Nullable Consumer<TyckUnit> parentAdd
) {
  // TODO(wsx): Visitor
  public @NotNull Expr resolve(@NotNull Expr expr, @NotNull Context ctx) {
    return switch (expr) {
      case Expr.LiftExpr lift -> {
        var mapped = resolve(lift.expr(), ctx);
        if (mapped == lift.expr()) yield expr;
        yield new Expr.LiftExpr(expr.sourcePos(), mapped, lift.lift());
      }
      case Expr.AppExpr app -> {
        var function = resolve(app.function(), ctx);
        var argument = app.argument();
        var argExpr = resolve(argument.expr(), ctx);
        if (function == app.function() && argExpr == argument.expr()) yield app;
        var newArg = new Expr.NamedArg(argument.explicit(), argument.name(), argExpr);
        yield new Expr.AppExpr(app.sourcePos(), function, newArg);
      }
      case Expr.Do doNotation -> {
        var bindsCtx = resolveDoBinds(doNotation.binds(), ctx);
        yield new Expr.Do(doNotation.sourcePos(), resolve(doNotation.bindName(), ctx), bindsCtx._1);
      }
      case Expr.TupExpr tup -> {
        var items = tup.items().map(item -> resolve(item, ctx));
        if (items.sameElements(tup.items(), true)) yield expr;
        yield new Expr.TupExpr(expr.sourcePos(), items);
      }
      case Expr.NewExpr newExpr -> {
        var struct = resolve(newExpr.struct(), ctx);
        var fields = newExpr.fields().map(t -> resolveField(t, ctx));
        if (newExpr.struct() == struct && fields.sameElements(newExpr.fields(), true)) yield newExpr;
        yield new Expr.NewExpr(newExpr.sourcePos(), struct, fields);
      }
      case Expr.BinOpSeq binOpSeq -> new Expr.BinOpSeq(binOpSeq.sourcePos(),
        binOpSeq.seq().map(e -> new Expr.NamedArg(e.explicit(), e.name(), resolve(e.expr(), ctx))));
      case Expr.ProjExpr proj -> {
        var tup = resolve(proj.tup(), ctx);
        // before desugar, we can only have integer projections.
        yield new Expr.ProjExpr(expr.sourcePos(), tup, proj.ix(), proj.resolvedVar(), proj.theCore());
      }
      case Expr.RawProjExpr proj -> {
        var tup = resolve(proj.tup(), ctx);
        var resolvedIx = ctx.getMaybe(proj.id());
        if (resolvedIx == null)
          ctx.reportAndThrow(new FieldError.UnknownField(proj.id().sourcePos(), proj.id().join()));
        var coeLeft = proj.coeLeft() != null ? resolve(proj.coeLeft(), ctx) : null;
        var restr = proj.restr() != null ? resolve(proj.restr(), ctx) : null;
        yield new Expr.RawProjExpr(proj.sourcePos(), tup, proj.id(), resolvedIx, coeLeft, restr);
      }
      case Expr.Match match -> {
        var discriminant = match.discriminant().map(e -> resolve(e, ctx));
        var clauses = match.clauses().map(c -> {
          var body = c.expr.map(i -> resolve(i, ctx));
          return new Pattern.Clause(c.sourcePos, c.patterns, body);
        });
        yield new Expr.Match(match.sourcePos(), discriminant, clauses);
      }
      case Expr.LamExpr lam -> {
        var param = resolveParam(lam.param(), ctx);
        var body = resolve(lam.body(), param._2);
        yield new Expr.LamExpr(lam.sourcePos(), param._1, body);
      }
      case Expr.SigmaExpr sigma -> {
        var params = resolveParams(sigma.params(), ctx);
        yield new Expr.SigmaExpr(sigma.sourcePos(), sigma.co(), params._1.toImmutableSeq());
      }
      case Expr.PiExpr pi -> {
        var param = resolveParam(pi.param(), ctx);
        var last = resolve(pi.last(), param._2);
        yield new Expr.PiExpr(pi.sourcePos(), pi.co(), param._1, last);
      }
      case Expr.HoleExpr hole -> {
        hole.accessibleLocal().set(ctx.collect(MutableList.create()).toImmutableSeq());
        var h = hole.filling() != null ? resolve(hole.filling(), ctx) : null;
        if (h == hole.filling()) yield hole;
        yield new Expr.HoleExpr(hole.sourcePos(), hole.explicit(), h, hole.accessibleLocal());
      }
      case Expr.PartEl el -> partial(ctx, el);
      case Expr.Path path -> {
        var newCtx = resolveCubeParams(path.params(), ctx);
        var par = partial(newCtx, path.partial());
        var type = resolve(path.type(), newCtx);
        if (type == path.type() && par == path.partial()) yield path;
        yield new Expr.Path(path.sourcePos(), path.params(), type, par);
      }
      case Expr.Array arrayExpr -> arrayExpr.arrayBlock().fold(
        left -> {
          var bindName = resolve(left.bindName(), ctx);
          var pureName = resolve(left.pureName(), ctx);
          var bindsCtx = resolveDoBinds(left.binds(), ctx);
          var generator = resolve(left.generator(), bindsCtx._2);

          if (generator == left.generator() && bindsCtx._1.sameElements(left.binds()) && bindName == left.bindName() && pureName == left.pureName()) {
            return arrayExpr;
          } else {
            return Expr.Array.newGenerator(arrayExpr.sourcePos(), generator, bindsCtx._1, bindName, pureName);
          }
        },
        right -> {
          var exprs = right.exprList().map(e -> resolve(e, ctx));

          if (exprs.sameElements(right.exprList())) {
            return arrayExpr;
          } else {
            return Expr.Array.newList(arrayExpr.sourcePos(), exprs);
          }
        }
      );
      case Expr.UnresolvedExpr unresolved -> {
        var sourcePos = unresolved.sourcePos();
        yield switch (ctx.get(unresolved.name())) {
          case GeneralizedVar generalized -> {
            if (options.allowGeneralized) {
              // Ordered set semantics. Do not expect too many generalized vars.
              if (!allowedGeneralizes.containsKey(generalized)) {
                var owner = generalized.owner;
                assert owner != null : "Sanity check";
                allowedGeneralizes.put(generalized, owner.toExpr(false, generalized.toLocal()));
                addReference(owner);
              }
            } else if (!allowedGeneralizes.containsKey(generalized))
              generalizedUnavailable(ctx, sourcePos, generalized);
            yield new Expr.RefExpr(sourcePos, allowedGeneralizes.get(generalized).ref());
          }
          case DefVar<?, ?> ref -> {
            switch (ref.concrete) {
              case null -> {
                // RefExpr is referring to a serialized core which is already tycked.
                // Collecting tyck order for tycked terms is unnecessary, just skip.
                assert ref.core != null; // ensure it is tycked
              }
              case TyckUnit unit -> addReference(unit);
            }
            yield new Expr.RefExpr(sourcePos, ref);
          }
          case AnyVar var -> new Expr.RefExpr(sourcePos, var);
        };
      }
      case Expr.Idiom idiom -> {
        var newNames = idiom.names().fmap(e -> resolve(e, ctx));
        var newBody = idiom.barredApps().map(e -> resolve(e, ctx));
        yield new Expr.Idiom(idiom.sourcePos(), newNames, newBody);
      }
      default -> expr;
    };
  }

  private @NotNull Expr.PartEl partial(@NotNull Context ctx, Expr.PartEl el) {
    var partial = el.clauses().map(e ->
      Tuple.of(resolve(e._1, ctx), resolve(e._2, ctx)));
    if (partial.zipView(el.clauses())
      .allMatch(p -> p._1._1 == p._2._1 && p._1._2 == p._2._2)) return el;
    return new Expr.PartEl(el.sourcePos(), partial);
  }

  enum Where {
    Head, Body
  }

  public void enterHead() {
    where.push(Where.Head);
    reference.clear();
  }

  public void enterBody() {
    where.push(Where.Body);
    reference.clear();
  }

  private void addReference(@NotNull TyckUnit unit) {
    if (parentAdd != null) parentAdd.accept(unit);
    if (where.isEmpty()) throw new InternalException("where am I?");
    if (where.peek() == Where.Head) {
      reference.append(new TyckOrder.Head(unit));
      reference.append(new TyckOrder.Body(unit));
    } else {
      reference.append(new TyckOrder.Body(unit));
    }
  }

  /**
   * @param allowLevels true for signatures, false for bodies
   */
  public record Options(boolean allowLevels, boolean allowGeneralized) {
  }

  public static final @NotNull Options RESTRICTIVE = new Options(false, false);
  public static final @NotNull Options LAX = new ExprResolver.Options(true, true);

  public ExprResolver(@NotNull Options options) {
    this(options, MutableLinkedHashMap.of(), MutableList.create(), MutableStack.create(), null);
  }

  public @NotNull ExprResolver member(@NotNull TyckUnit decl) {
    return new ExprResolver(RESTRICTIVE, allowedGeneralizes, MutableList.of(new TyckOrder.Head(decl)), MutableStack.create(),
      this::addReference);
  }

  public @NotNull ExprResolver body() {
    return new ExprResolver(RESTRICTIVE, allowedGeneralizes, reference, MutableStack.create(),
      this::addReference);
  }

  private void generalizedUnavailable(Context ctx, SourcePos refExpr, AnyVar var) {
    ctx.reporter().report(new GeneralizedNotAvailableError(refExpr, var));
    throw new Context.ResolvingInterruptedException();
  }

  private @NotNull Tuple2<Expr.Param, Context> resolveParam(@NotNull Expr.Param param, Context ctx) {
    var type = resolve(param.type(), ctx);
    return Tuple.of(new Expr.Param(param, type), ctx.bind(param.ref(), param.sourcePos()));
  }

  /**
   * Resolving all {@link Expr.DoBind}s under the context {@param parent}
   *
   * @return (resolved bindings, a new context under all the bindings)
   */
  private @NotNull Tuple2<ImmutableSeq<Expr.DoBind>, Context> resolveDoBinds(@NotNull ImmutableSeq<Expr.DoBind> binds, Context parent) {
    var list = MutableArrayList.<Expr.DoBind>create(binds.size());
    var localCtx = parent;

    for (var bind : binds) {
      var bindResolved = resolve(bind.expr(), localCtx);
      if (bindResolved == bind.expr()) {
        list.append(bind);
      } else {
        list.append(new Expr.DoBind(bind.sourcePos(), bind.var(), bindResolved));
      }

      localCtx = localCtx.bind(bind.var(), bind.sourcePos());
    }

    return Tuple.of(list.toImmutableSeq(), localCtx);
  }

  private @NotNull Context resolveCubeParams(@NotNull ImmutableSeq<LocalVar> params, Context ctx) {
    return params.foldLeft(ctx, (c, x) -> c.bind(x, x.definition()));
  }

  @Contract(pure = true)
  public @NotNull Tuple2<SeqView<Expr.Param>, Context>
  resolveParams(@NotNull SeqLike<Expr.Param> params, Context ctx) {
    if (params.isEmpty()) return Tuple.of(SeqView.empty(), ctx);
    var first = params.first();
    var type = resolve(first.type(), ctx);
    var newCtx = ctx.bind(first.ref(), first.sourcePos());
    var result = resolveParams(params.view().drop(1), newCtx);
    return Tuple.of(result._1.prepended(new Expr.Param(first, type)), result._2);
  }

  private Expr.@NotNull Field resolveField(Expr.@NotNull Field t, Context context) {
    for (var binding : t.bindings()) context = context.bind(binding.data(), binding.sourcePos());
    var accept = resolve(t.body(), context);
    if (accept == t.body()) return t;
    return new Expr.Field(t.name(), t.bindings(), accept, t.resolvedField());
  }
}
