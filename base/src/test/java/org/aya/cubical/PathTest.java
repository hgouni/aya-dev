// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.cubical;

import org.aya.pretty.doc.Doc;
import org.aya.tyck.TyckDeclTest;
import org.aya.util.distill.DistillerOptions;
import org.junit.jupiter.api.Test;

import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PathTest {
  @Test public void refl() {
    var res = TyckDeclTest.successTyckDecls("""
      def infix = {A : Type} (a b : A) : Type =>
        [| i |] A {| i 0 := a | i 1 := b |}
          
      def idp {A : Type} {a : A} : a = a =>
        \\i => a
      """);
    IntFunction<Doc> distiller = i -> res._2.get(i).toDoc(DistillerOptions.debug());
    assertEquals("""
      def = {A : Type 0} (a b : A) : Type 0 => [| i |] A {| i 0 := a | i 1 := b |}
      """.strip(), distiller.apply(0).debugRender());
    assertEquals("""
      def idp {A : Type 0} {a : A} : (=) {A} a a => \\ (i : I) => a
      """.strip(), distiller.apply(1).debugRender());
  }

  @Test public void cong() {
    TyckDeclTest.successTyckDecls("""
      def infix = {A : Type} (a b : A) : Type =>
      [| i |] A {| i 0 := a | i 1 := b |}
          
      def idp {A : Type} {a : A} : a = a =>
        \\i => a
            
      def cong
        {A B : Type}
        (f : A -> B)
        (a b : A)
        (p : a = b)
        : --------------
        f a = f b
        => \\i => f (p i)
      """);
  }

  @Test public void funExt() {
    TyckDeclTest.successTyckDecls("""
      def infix = {A : Type} (a b : A) : Type =>
      [| i |] A {| i 0 := a | i 1 := b |}
          
      def idp {A : Type} {a : A} : a = a =>
        \\i => a
            
      def funExt
        {A B : Type}
        (f g : A -> B)
        (p : Pi (a : A) -> f a = g a)
        : ---------------------------------
        f = g
        => \\i a => p a i
      """);
  }

  @Test public void partialConv() {
    TyckDeclTest.successTyckDecls("""
      def infix = {A : Type} (a b : A) : Type =>
        [| i |] A {| i 0 := a | i 1 := b |}
          
      def idp {A : Type} {a : A} : a = a =>
        \\i => a
        
      def p1 (A : Type) (a : A) (i : I) : Partial A {i 0} =>
        {| i 0 := a |}
      def p2 (A : Type) (b : A) (j : I) : Partial A {j 0} =>
        {| j 0 := b |}
      def p1=p2 (A : Type) (a : A) (i : I) : p1 A a i = p2 A a i =>
        idp
          
      def cmp {A : Type} (x : A)
        : [| i j |] (Partial A {j 0}) {| i 0 := p1 A x j |}
        => \\i j => p2 A x j
      """);
  }
}