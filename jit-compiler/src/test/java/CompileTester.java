// Copyright (c) 2020-2024 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.

import kala.collection.immutable.ImmutableSeq;
import org.aya.compiler.AyaSerializer;
import org.aya.resolve.module.DumbModuleLoader;
import org.aya.syntax.compile.JitDef;
import org.jetbrains.annotations.NotNull;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CompileTester {
  public final @NotNull String code;
  private final Path baka;
  public final ClassLoader cl;

  public CompileTester(@NotNull String code) throws IOException {
    this.code = code;

    var genDir = Paths.get("src/test/gen");
    Files.createDirectories(genDir);
    Files.writeString(baka = genDir.resolve("baka.java"), code);
    cl = new URLClassLoader(new URL[]{baka.toUri().toURL()});
  }

  public void compile() {
    try {
      var compiler = ToolProvider.getSystemJavaCompiler();
      var fileManager = compiler.getStandardFileManager(null, null, null);
      var compilationUnits = fileManager.getJavaFileObjects(baka);
      var task = compiler.getTask(null, fileManager, null, null, null, compilationUnits);
      task.call();
      var fqName = STR."\{AyaSerializer.PACKAGE_BASE}.\{DumbModuleLoader.DUMB_MODULE_NAME}";
      cl.loadClass(fqName);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unchecked")
  public <T> @NotNull Class<T> load(String... qualified) {
    try {
      return (Class<T>) cl.loadClass(
        STR."\{AyaSerializer.PACKAGE_BASE}.\{ImmutableSeq.from(qualified).joinToString("$")}");
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unchecked")
  public <T extends JitDef> T getInstance(@NotNull Class<T> clazz) {
    try {
      Field field = clazz.getField(AyaSerializer.STATIC_FIELD_INSTANCE);
      field.setAccessible(true);
      return (T) field.get(null);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  public <T extends JitDef> T loadInstance(String... qualified) {
    return getInstance(load(qualified));
  }
}
