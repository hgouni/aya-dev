// Copyright (c) 2020-2021 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.concrete.resolve.module;

import kala.collection.Seq;
import kala.collection.immutable.ImmutableSeq;
import org.aya.api.error.DelayedReporter;
import org.aya.api.error.Reporter;
import org.aya.api.error.SourceFileLocator;
import org.aya.api.util.InternalException;
import org.aya.concrete.Expr;
import org.aya.concrete.desugar.AyaBinOpSet;
import org.aya.concrete.parse.AyaParsing;
import org.aya.concrete.resolve.ModuleCallback;
import org.aya.concrete.resolve.ResolveInfo;
import org.aya.concrete.resolve.context.EmptyContext;
import org.aya.concrete.resolve.context.ModuleContext;
import org.aya.concrete.stmt.Stmt;
import org.aya.tyck.ExprTycker;
import org.aya.tyck.order.AyaIncrementalTycker;
import org.aya.tyck.order.AyaSccTycker;
import org.aya.tyck.trace.Trace;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

public record FileModuleLoader(
  @NotNull SourceFileLocator locator,
  @NotNull Path basePath,
  @NotNull Reporter reporter,
  Trace.@Nullable Builder builder
) implements ModuleLoader {
  @Override
  public @Nullable ResolveInfo load(@NotNull ImmutableSeq<@NotNull String> path, @NotNull ModuleLoader recurseLoader) {
    var sourcePath = resolveFile(basePath, path);
    try {
      var program = AyaParsing.program(locator, reporter, sourcePath);
      var context = new EmptyContext(reporter, sourcePath).derive(path);
      return tyckModule(context, recurseLoader, program, reporter, builder, null);
    } catch (IOException e) {
      return null;
    }
  }

  public static ResolveInfo resolveModule(
    @NotNull ModuleContext context,
    @NotNull ModuleLoader recurseLoader,
    @NotNull ImmutableSeq<Stmt> program,
    @NotNull Reporter reporter
  ) {
    var resolveInfo = new ResolveInfo(context, program, new AyaBinOpSet(reporter));
    Stmt.resolve(program, resolveInfo, recurseLoader);
    return resolveInfo;
  }

  public static <E extends Exception> @NotNull ResolveInfo tyckModule(
    @NotNull ModuleContext context,
    @NotNull ModuleLoader recurseLoader,
    @NotNull ImmutableSeq<Stmt> program,
    @NotNull Reporter reporter,
    @Nullable Trace.Builder builder,
    @Nullable ModuleCallback<E> onTycked
  ) throws E {
    var resolveInfo = resolveModule(context, recurseLoader, program, reporter);
    tyckResolvedModule(resolveInfo, reporter, builder, onTycked);
    return resolveInfo;
  }

  public static <E extends Exception> void tyckResolvedModule(
    @NotNull ResolveInfo resolveInfo,
    @NotNull Reporter reporter,
    Trace.@Nullable Builder builder,
    @Nullable ModuleCallback<E> onTycked
  ) throws E {
    var program = resolveInfo.thisProgram();
    var delayedReporter = new DelayedReporter(reporter);
    var sccTycker = new AyaIncrementalTycker(new AyaSccTycker(builder, delayedReporter), resolveInfo);
    // in case we have un-messaged TyckException
    try (delayedReporter) {
      var SCCs = resolveInfo.declGraph().topologicalOrder()
        .view().appendedAll(resolveInfo.sampleGraph().topologicalOrder())
        .toImmutableSeq();
      SCCs.forEach(sccTycker::tyckSCC);
    } finally {
      if (onTycked != null) onTycked.onModuleTycked(resolveInfo, program,
        sccTycker.sccTycker().wellTyped().toImmutableSeq());
    }
  }

  /**
   * Copied and adapted.
   *
   * @see #tyckModule
   */
  public static ExprTycker.@NotNull Result tyckExpr(
    @NotNull ModuleContext context,
    @NotNull Expr expr,
    @NotNull Reporter reporter,
    Trace.@Nullable Builder builder
  ) {
    var resolvedExpr = expr.resolve(context);
    // in case we have un-messaged TyckException
    try (var delayedReporter = new DelayedReporter(reporter)) {
      var tycker = new ExprTycker(delayedReporter, builder);
      return tycker.zonk(expr, tycker.synthesize(resolvedExpr.desugar(delayedReporter)));
    }
  }

  public static @NotNull Path resolveFile(@NotNull Path basePath, @NotNull Seq<@NotNull String> moduleName) {
    var withoutExt = moduleName.foldLeft(basePath, Path::resolve);
    return withoutExt.resolveSibling(withoutExt.getFileName() + ".aya");
  }

  public static void handleInternalError(@NotNull InternalException e) {
    e.printStackTrace();
    e.printHint();
    System.err.println("""
      Please report the stacktrace to the developers so a better error handling could be made.
      Don't forget to inform the version of Aya you're using and attach your code for reproduction.""");
  }
}
