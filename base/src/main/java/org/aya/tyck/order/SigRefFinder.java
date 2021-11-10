// Copyright (c) 2020-2021 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.tyck.order;

import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.DynamicSeq;
import kala.tuple.Unit;
import org.aya.api.ref.DefVar;
import org.aya.concrete.Expr;
import org.aya.concrete.remark.Remark;
import org.aya.concrete.stmt.*;
import org.aya.concrete.visitor.ExprConsumer;
import org.jetbrains.annotations.NotNull;

/**
 * Concrete version of RefFinder but only header is searched.
 *
 * @author kiva
 * @see org.aya.core.visitor.RefFinder
 */
public class SigRefFinder implements
  Stmt.Visitor<@NotNull DynamicSeq<Stmt>, Unit>,
  ExprConsumer<@NotNull DynamicSeq<Stmt>> {
  public static final @NotNull SigRefFinder HEADER_ONLY = new SigRefFinder();

  private void tele(@NotNull DynamicSeq<Stmt> stmts, @NotNull ImmutableSeq<Expr.Param> telescope) {
    telescope.mapNotNull(Expr.Param::type).forEach(type -> type.accept(this, stmts));
  }

  @Override public Unit visitRef(@NotNull Expr.RefExpr expr, @NotNull DynamicSeq<Stmt> stmts) {
    if (expr.resolvedVar() instanceof DefVar<?, ?> defVar && defVar.concrete instanceof Decl decl)
      stmts.append(decl);
    return Unit.unit();
  }

  @Override public Unit visitData(@NotNull Decl.DataDecl decl, @NotNull DynamicSeq<Stmt> stmts) {
    tele(stmts, decl.telescope);
    decl.result.accept(this, stmts);
    return Unit.unit();
  }

  @Override public Unit visitStruct(@NotNull Decl.StructDecl decl, @NotNull DynamicSeq<Stmt> stmts) {
    tele(stmts, decl.telescope);
    decl.result.accept(this, stmts);
    return Unit.unit();
  }

  @Override public Unit visitFn(@NotNull Decl.FnDecl decl, @NotNull DynamicSeq<Stmt> stmts) {
    tele(stmts, decl.telescope);
    decl.result.accept(this, stmts);
    return Unit.unit();
  }

  @Override public Unit visitPrim(@NotNull Decl.PrimDecl decl, @NotNull DynamicSeq<Stmt> stmts) {
    return Unit.unit();
  }

  @Override public Unit visitCtor(@NotNull Decl.DataCtor ctor, @NotNull DynamicSeq<Stmt> stmts) {
    return Unit.unit();
  }

  @Override public Unit visitField(@NotNull Decl.StructField field, @NotNull DynamicSeq<Stmt> stmts) {
    return Unit.unit();
  }

  @Override public Unit visitImport(Command.@NotNull Import cmd, @NotNull DynamicSeq<Stmt> stmts) {
    return Unit.unit();
  }

  @Override public Unit visitOpen(Command.@NotNull Open cmd, @NotNull DynamicSeq<Stmt> stmts) {
    return Unit.unit();
  }

  @Override public Unit visitModule(Command.@NotNull Module mod, @NotNull DynamicSeq<Stmt> stmts) {
    return Unit.unit();
  }

  @Override public Unit visitBind(Command.@NotNull Bind bind, @NotNull DynamicSeq<Stmt> stmts) {
    return Unit.unit();
  }

  @Override public Unit visitLevels(Generalize.@NotNull Levels levels, @NotNull DynamicSeq<Stmt> stmts) {
    return Unit.unit();
  }

  @Override public Unit visitRemark(@NotNull Remark remark, @NotNull DynamicSeq<Stmt> stmts) {
    return Unit.unit();
  }

  @Override public Unit visitExample(Sample.@NotNull Working example, @NotNull DynamicSeq<Stmt> stmts) {
    example.delegate().accept(this, stmts);
    return Unit.unit();
  }

  @Override public Unit visitCounterexample(Sample.@NotNull Counter example, @NotNull DynamicSeq<Stmt> stmts) {
    example.delegate().accept(this, stmts);
    return Unit.unit();
  }
}