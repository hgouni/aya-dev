// Copyright (c) 2020-2023 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.resolve.context;

import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.MutableMap;
import org.aya.concrete.stmt.QualifiedID;
import org.aya.concrete.stmt.Stmt;
import org.aya.concrete.stmt.UseHide;
import org.aya.generic.Constants;
import org.aya.ref.AnyVar;
import org.aya.ref.DefVar;
import org.aya.resolve.error.NameProblem;
import org.aya.util.error.SourcePos;
import org.aya.util.error.WithPos;
import org.aya.util.reporter.Reporter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * @author re-xyr
 */
public sealed interface ModuleContext extends Context permits NoExportContext, PhysicalModuleContext {
  @Override @NotNull Context parent();
  @Override default @NotNull Reporter reporter() {
    return parent().reporter();
  }
  @Override default @NotNull Path underlyingFile() {
    return parent().underlyingFile();
  }


  /**
   * All available symbols in this context<br>
   * {@code Unqualified -> (Module Name -> TopLevel)}<br>
   * It says an {@link AnyVar} can be referred by {@code {Module Name}::{Unqualified}}
   */
  @NotNull ModuleSymbol<AnyVar> symbols();

  /**
   * All imported modules in this context.<br/>
   * {@code Qualified Module -> Module Export}
   *
   * @apiNote empty list => this module
   * @implNote This module should be automatically imported.
   */
  @NotNull MutableMap<ModulePath.Qualified, ModuleExport> modules();


  /**
   * Modules that are exported by this module.
   */
  @NotNull MutableMap<ModulePath, ModuleExport> exports();

  @Override default @Nullable ModuleExport getModuleLocalMaybe(@NotNull ModulePath.Qualified modName) {
    return modules().getOrNull(modName);
  }

  @Override default @Nullable AnyVar getUnqualifiedLocalMaybe(@NotNull String name, @NotNull SourcePos sourcePos) {
    var symbol = symbols().getUnqualifiedMaybe(name);
    if (symbol.isOk()) return symbol.get();

    // I am sure that this is not equivalent to null
    return switch (symbol.getErr()) {
      case NotFound -> null;
      case Ambiguous -> reportAndThrow(new NameProblem.AmbiguousNameError(
        name,
        ImmutableSeq.narrow(symbols().resolveUnqualified(name).keysView().map(ModulePath::ids).toImmutableSeq()),
        sourcePos));
    };
  }

  @Override
  default @Nullable AnyVar getQualifiedLocalMaybe(@NotNull ModulePath.Qualified modName, @NotNull String name, @NotNull SourcePos sourcePos) {
    var mod = modules().getOrNull(modName);
    if (mod == null) return null;

    var ref = mod.symbols().getUnqualifiedMaybe(name);
    if (ref.isOk()) return ref.get();

    return switch (ref.getErr()) {
      case NotFound -> reportAndThrow(new NameProblem.QualifiedNameNotFoundError(modName, name, sourcePos));
      case Ambiguous -> reportAndThrow(new NameProblem.AmbiguousNameError(
        name,
        ImmutableSeq.narrow(mod.symbols().resolveUnqualified(name).keysView().map(ModulePath::ids).toImmutableSeq()),
        sourcePos
      ));
    };
  }

  /**
   * Import the whole module (including itself and its re-exports)
   *
   * @see ModuleContext#importModule(ModulePath.Qualified, ModuleExport, Stmt.Accessibility, SourcePos)
   */
  default void importModule(
    @NotNull ModulePath.Qualified modName,
    @NotNull ModuleContext module,
    @NotNull Stmt.Accessibility accessibility,
    @NotNull SourcePos sourcePos
  ) {
    module.exports().forEach((name, mod) -> importModule(modName.concat(name), mod, accessibility, sourcePos));
  }

  /**
   * Importing one module export.
   *
   * @param accessibility of importing, re-export if public
   * @param modName       the name of the module
   * @param moduleExport  the module
   */
  default void importModule(
    @NotNull ModulePath.Qualified modName,
    @NotNull ModuleExport moduleExport,
    @NotNull Stmt.Accessibility accessibility,
    @NotNull SourcePos sourcePos
  ) {
    var modules = modules();
    if (modules.containsKey(modName)) {
      reportAndThrow(new NameProblem.DuplicateModNameError(modName.ids(), sourcePos));
    }
    if (getModuleMaybe(modName) != null) {
      reporter().report(new NameProblem.ModShadowingWarn(modName.ids(), sourcePos));
    }
    modules.set(modName, moduleExport);
  }

  /**
   * Open an imported module
   *
   * @param modName the name of the module
   * @param filter  use or hide which definitions
   * @param rename  renaming
   */
  default void openModule(
    @NotNull ModulePath.Qualified modName,
    @NotNull Stmt.Accessibility accessibility,
    @NotNull ImmutableSeq<QualifiedID> filter,
    @NotNull ImmutableSeq<WithPos<UseHide.Rename>> rename,
    @NotNull SourcePos sourcePos,
    UseHide.Strategy strategy
  ) {
    var modExport = getModuleMaybe(modName);
    if (modExport == null) reportAndThrow(new NameProblem.ModNameNotFoundError(modName, sourcePos));

    var filterRes = modExport.filter(filter, strategy);
    if (filterRes.anyError()) reportAllAndThrow(filterRes.problems(modName));

    var filtered = filterRes.result();
    var mapRes = filtered.map(rename);
    if (mapRes.anyError()) reportAllAndThrow(mapRes.problems(modName));

    var renamed = mapRes.result();
    renamed.symbols().forEach((name, candidates) -> candidates.forEach((componentName, ref) -> {
      var fullComponentName = modName.concat(componentName);
      var symbol = new GlobalSymbol.Imported(fullComponentName, name, ref, accessibility);
      addGlobal(symbol, sourcePos);
    }));

    // report all warning
    reportAll(filterRes.problems(modName).concat(mapRes.problems(modName)));
  }

  default void define(@NotNull AnyVar defined, @NotNull Stmt.Accessibility accessibility, @NotNull SourcePos sourcePos) {
    addGlobal(new GlobalSymbol.Defined(defined.name(), defined, accessibility), sourcePos);
  }

  /**
   * Adding a new symbol to this module.
   */
  default void addGlobal(
    @NotNull ModuleContext.GlobalSymbol symbol,
    @NotNull SourcePos sourcePos
  ) {
    var modName = symbol.componentName();
    var name = symbol.unqualifiedName();
    var symbols = symbols();
    if (!symbols.contains(name)) {
      if (getUnqualifiedMaybe(name, sourcePos) != null && !name.startsWith(Constants.ANONYMOUS_PREFIX)) {
        reporter().report(new NameProblem.ShadowingWarn(name, sourcePos));
      }
    } else if (symbols.containsDefinitely(modName, name)) {
      reportAndThrow(new NameProblem.DuplicateNameError(name, symbol.data(), sourcePos));
    } else {
      reporter().report(new NameProblem.AmbiguousNameWarn(name, sourcePos));
    }

    switch (symbol) {
      case GlobalSymbol.Defined defined -> {
        // Defined, not imported.
        var result = symbols.add(ModulePath.This, name, defined.data());
        assert result.isEmpty() : "Sanity check";
        doDefine(name, defined.data(), sourcePos);
      }
      case GlobalSymbol.Imported imported -> {
        var result = symbols.add(modName, name, imported.data());
        assert result.isEmpty() : "Sanity check";
      }
    }

    var exportSymbol = symbol.exportMaybe();
    if (exportSymbol != null) {
      doExport(modName, name, exportSymbol, sourcePos);
    }
  }

  default void doDefine(@NotNull String name, @NotNull AnyVar ref, @NotNull SourcePos sourcePos) {
    // TODO: do nothing?
  }

  /**
   * Exporting an {@link AnyVar} with qualified id {@code {componentName}::{name}}
   */
  void doExport(@NotNull ModulePath componentName, @NotNull String name, @NotNull DefVar<?, ?> ref, @NotNull SourcePos sourcePos);

  // TODO: This is only used in ModuleContext#addGlobal
  sealed interface GlobalSymbol {
    @NotNull AnyVar data();
    @NotNull ModulePath componentName();
    @NotNull String unqualifiedName();

    /**
     * @return null if not visible to outside
     */
    @Nullable DefVar<?, ?> exportMaybe();

    record Defined(
      @NotNull String unqualifiedName,
      @NotNull AnyVar data,
      @NotNull Stmt.Accessibility accessibility
    ) implements GlobalSymbol {
      @Override
      public @NotNull ModulePath.This componentName() {
        return ModulePath.This;
      }

      @Override
      public @Nullable DefVar<?, ?> exportMaybe() {
        if (data instanceof DefVar<?, ?> defVar && accessibility() == Stmt.Accessibility.Public) {
          return defVar;
        } else {
          return null;
        }
      }
    }

    record Imported(
      @NotNull ModulePath.Qualified componentName,
      @NotNull String unqualifiedName,
      @NotNull DefVar<?, ?> data,
      @NotNull Stmt.Accessibility accessibility
    ) implements GlobalSymbol {

      @Override
      public @Nullable DefVar<?, ?> exportMaybe() {
        if (accessibility == Stmt.Accessibility.Public) {
          return data;
        } else {
          return null;
        }
      }
    }
  }
}
