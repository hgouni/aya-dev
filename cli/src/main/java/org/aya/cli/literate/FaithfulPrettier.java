// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.cli.literate;

import com.intellij.openapi.util.text.StringUtil;
import kala.collection.SeqLike;
import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.MutableList;
import kala.text.StringView;
import kala.tuple.Tuple;
import kala.tuple.Tuple4;
import org.aya.prettier.AyaPrettierOptions;
import org.aya.prettier.BasePrettier;
import org.aya.generic.AyaDocile;
import org.aya.pretty.doc.Doc;
import org.aya.util.error.SourcePos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface FaithfulPrettier {
  static void checkHighlights(@NotNull SeqLike<HighlightInfo> highlights) {
    var lastEndIndex = -1;

    for (var highlight : highlights) {
      var sp = highlight.sourcePos();

      if (!(sp.tokenStartIndex() <= sp.tokenEndIndex())) {
        throw new IllegalArgumentException("Invalid source pos: " + sp);
      }

      if (!(lastEndIndex < sp.tokenStartIndex())) {
        throw new IllegalArgumentException("Intersect with previous source pos: " + sp);
      }

      lastEndIndex = sp.tokenEndIndex();
    }
  }

  /**
   * Apply highlights to source code string.
   *
   * @param raw        the source code
   * @param base       where the raw start from (the 'raw' might be a piece of the source code,
   *                   so it probably not starts from 0).
   * @param highlights the highlights for the source code
   */
  static @NotNull Doc highlight(@NotNull String raw, int base, @NotNull ImmutableSeq<HighlightInfo> highlights) {
    highlights = highlights.sorted().view()
      .distinct()
      .filter(h -> h.sourcePos() != SourcePos.NONE)
      .filterNot(h -> h.sourcePos().isEmpty())
      .toImmutableSeq();
    checkHighlights(highlights);

    return doHighlight(StringView.of(raw), base, highlights);
  }

  private static @NotNull Doc doHighlight(@NotNull StringView raw, int base, @NotNull ImmutableSeq<HighlightInfo> highlights) {
    var docs = MutableList.<Doc>create();

    for (var current : highlights) {
      var parts = twoKnifeThreeParts(raw, base, current.sourcePos());
      if (!parts._1.isEmpty()) docs.append(Doc.plain(parts._1.toString()));
      var highlightPart = highlightOne(parts._2.toString(), current.type());
      var remainPart = parts._3;
      var newBase = parts._4;

      if (highlightPart != Doc.empty()) {
        // Hit if:
        // * SourcePos contains nothing
        docs.append(highlightPart);
      }

      raw = remainPart;
      base = newBase;
    }

    if (!raw.isEmpty()) docs.append(Doc.plain(raw.toString()));

    return Doc.cat(docs);
  }

  private static @NotNull Doc highlightOne(@NotNull String raw, @NotNull HighlightInfo.HighlightSymbol highlight) {
    if (raw.isEmpty()) return Doc.empty();
    return switch (highlight) {
      case HighlightInfo.SymDef symDef ->
        Doc.linkDef(highlightVar(raw, symDef.kind()), symDef.target(), hover(symDef.type()));
      case HighlightInfo.SymRef symRef ->
        Doc.linkRef(highlightVar(raw, symRef.kind()), symRef.target(), hover(symRef.type()));
      case HighlightInfo.SymLit symLit -> highlightLit(raw, symLit.kind());
      case HighlightInfo.SymError symError -> Doc.plain(raw);   // TODO: any style for error?
    };
  }

  private static @Nullable String hover(@Nullable AyaDocile term) {
    if (term == null) return null;
    return term.toDoc(AyaPrettierOptions.pretty()).commonRender(); // TODO: prettier options
  }

  private static @NotNull Doc highlightVar(@NotNull String raw, @NotNull HighlightInfo.DefKind defKind) {
    var style = switch (defKind) {
      case Data -> BasePrettier.DATA;
      case Con -> BasePrettier.CON;
      case Struct -> BasePrettier.STRUCT;
      case Field -> BasePrettier.FIELD;
      case Fn -> BasePrettier.FN;
      case Prim -> BasePrettier.PRIM;
      case Generalized -> BasePrettier.GENERALIZED;
      case LocalVar, Unknown, Module -> null;
    };

    if (style != null) {
      return Doc.styled(style, raw);
    } else {
      return Doc.plain(raw);
    }
  }

  private static @NotNull Doc highlightLit(@NotNull String raw, @NotNull HighlightInfo.LitKind litKind) {
    return switch (litKind) {
      case Int -> Doc.plain(raw);
      case String -> Doc.plain(StringUtil.escapeStringCharacters(raw));
      case Keyword -> Doc.styled(BasePrettier.KEYWORD, raw);
    };
  }

  private static @NotNull Tuple4<StringView, StringView, StringView, Integer>
  twoKnifeThreeParts(@NotNull StringView raw, int base, @NotNull SourcePos twoKnife) {
    var beginPart1 = twoKnife.tokenStartIndex() - base;
    var endPart1 = twoKnife.tokenEndIndex() + 1 - base;
    var part0 = raw.substring(0, beginPart1);
    var part1 = raw.substring(beginPart1, endPart1);
    var part2 = raw.substring(endPart1, raw.length());

    return Tuple.of(part0, part1, part2, twoKnife.tokenEndIndex() + 1);
  }
}