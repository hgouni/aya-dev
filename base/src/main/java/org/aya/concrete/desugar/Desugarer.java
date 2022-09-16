// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.concrete.desugar;

import kala.tuple.Unit;
import org.aya.concrete.Expr;
import org.aya.concrete.Pattern;
import org.aya.concrete.error.LevelProblem;
import org.aya.concrete.visitor.ExprOps;
import org.aya.concrete.visitor.ExprView;
import org.aya.concrete.visitor.StmtOps;
import org.aya.core.term.FormTerm;
import org.aya.resolve.ResolveInfo;
import org.jetbrains.annotations.NotNull;

/**
 * @author ice1000, kiva
 */
public record Desugarer(@NotNull ResolveInfo resolveInfo) implements StmtOps<Unit> {
  public record ForExpr(@Override @NotNull ExprView view, @NotNull ResolveInfo info) implements ExprOps {
    private int levelVar(@NotNull Expr expr) throws DesugarInterruption {
      return switch (expr) {
        case Expr.BinOpSeq binOpSeq -> levelVar(pre(binOpSeq));
        case Expr.LitIntExpr uLit -> uLit.integer();
        default -> {
          info.opSet().reporter.report(new LevelProblem.BadLevelExpr(expr));
          throw new DesugarInterruption();
        }
      };
    }

    @Override public @NotNull Expr pre(@NotNull Expr expr) {
      return switch (expr) {
        // TODO: java 19
        case Expr.AppExpr app && app.function() instanceof Expr.RawSortExpr univ && univ.kind() == FormTerm.SortKind.Type -> {
          try {
            yield new Expr.TypeExpr(univ.sourcePos(), levelVar(app.argument().expr()));
          } catch (DesugarInterruption e) {
            yield new Expr.ErrorExpr(((Expr) app).sourcePos(), app);
          }
        }
        case Expr.AppExpr app && app.function() instanceof Expr.RawSortExpr univ && univ.kind() == FormTerm.SortKind.Set -> {
          try {
            yield new Expr.SetExpr(univ.sourcePos(), levelVar(app.argument().expr()));
          } catch (DesugarInterruption e) {
            yield new Expr.ErrorExpr(((Expr) app).sourcePos(), app);
          }
        }
        case Expr.RawSortExpr univ -> switch (univ.kind()) {
          case Type -> new Expr.TypeExpr(univ.sourcePos(), 0);
          case Set -> new Expr.SetExpr(univ.sourcePos(), 0);
          case Prop -> new Expr.PropExpr(univ.sourcePos());
          case ISet -> new Expr.ISetExpr(univ.sourcePos());
        };
        case Expr.BinOpSeq binOpSeq -> {
          var seq = binOpSeq.seq();
          assert seq.isNotEmpty() : binOpSeq.sourcePos().toString();
          yield pre(new BinExprParser(info, seq.view()).build(binOpSeq.sourcePos()));
        }
        case Expr misc -> misc;
      };
    }
  }

  @Override public @NotNull Expr visitExpr(@NotNull Expr expr, Unit pp) {
    return new ForExpr(expr.view(), resolveInfo).commit();
  }

  public static class DesugarInterruption extends Exception {
  }

  @Override public @NotNull Pattern visitBinOpPattern(Pattern.@NotNull BinOpSeq binOpSeq, Unit unit) {
    var seq = binOpSeq.seq();
    assert seq.isNotEmpty() : binOpSeq.sourcePos().toString();
    var pat = new BinPatternParser(binOpSeq.explicit(), resolveInfo, seq.view()).build(binOpSeq.sourcePos());
    return StmtOps.super.visitPattern(pat, unit);
  }
}
