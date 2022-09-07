// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.tyck.error;

import org.aya.concrete.Expr;
import org.aya.core.term.Term;
import org.aya.distill.BaseDistiller;
import org.aya.generic.ExprProblem;
import org.aya.guest0x0.cubical.Restr;
import org.aya.pretty.doc.Doc;
import org.aya.tyck.TyckState;
import org.aya.tyck.unify.DefEq;
import org.aya.util.distill.DistillerOptions;
import org.jetbrains.annotations.NotNull;

public sealed interface CubicalProblem extends ExprProblem {
  default @Override @NotNull Severity level() {
    return Severity.ERROR;
  }

  record DimensionMismatch(
    @NotNull Expr expr,
    int expectedDim,
    int actualDim
  ) implements CubicalProblem {
    @Override public @NotNull Doc describe(@NotNull DistillerOptions options) {
      return Doc.sep(Doc.english("This path lambda expects"),
        Doc.plain(String.valueOf(expectedDim)),
        Doc.english(expectedDim == 1 ? "parameter," : "parameters,"),
        Doc.english("but it has" + (actualDim < expectedDim ? " only" : "")),
        Doc.plain(String.valueOf(actualDim)));
    }
  }

  record BoundaryDisagree(
    @NotNull Expr expr,
    @NotNull Term lhs,
    @NotNull Term rhs,
    @Override @NotNull DefEq.FailureData failureData,
    @Override @NotNull TyckState state
  ) implements CubicalProblem, UnifyError {
    @Override public @NotNull Doc describe(@NotNull DistillerOptions options) {
      return describeUnify(options, Doc.english("The boundary"), lhs,
        Doc.english("disagrees with"), rhs);
    }
  }

  record FaceMismatch(
    @NotNull Expr expr,
    @NotNull Restr<Term> face,
    @NotNull Restr<Term> cof
  ) implements CubicalProblem {
    @Override
    public @NotNull Doc describe(@NotNull DistillerOptions options) {
      return Doc.vcat(Doc.english("The face(s) in the partial element:"),
        Doc.par(1, BaseDistiller.restr(options, face)),
        Doc.english("must cover the face(s) specified in type:"),
        Doc.par(1, BaseDistiller.restr(options, cof)));
    }
  }
}