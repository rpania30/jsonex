/*************************************************************
 Copyright 2018-2019 eBay Inc.
 Author/Developer: Jianwu Chen

 Use of this source code is governed by an MIT-style
 license that can be found in the LICENSE file or at
 https://opensource.org/licenses/MIT.
 ************************************************************/

package org.jsonex.treedoc.json;

import org.jsonex.core.charsource.ArrayCharSource;
import org.jsonex.core.charsource.CharSource;
import org.jsonex.core.charsource.ReaderCharSource;
import org.jsonex.core.factory.InjectableInstance;
import org.jsonex.core.util.ClassUtil;
import org.jsonex.treedoc.TDNode;
import org.jsonex.treedoc.TreeDoc;

import java.io.Reader;

public class TDJSONParser {
  private final static char EOF = '\uFFFF';
  public final static InjectableInstance<TDJSONParser> instance = InjectableInstance.of(TDJSONParser.class);
  public static TDJSONParser get() { return instance.get(); }

  public TDNode parse(String str) { return parse(str, new TDJSONOption()); }
  public TDNode parse(String str, TDJSONOption opt) { return parse(new ArrayCharSource(str), opt); }

  public TDNode parse(Reader reader) { return parse(reader, new TDJSONOption()); }
  public TDNode parse(Reader reader, TDJSONOption opt) { return parse(new ReaderCharSource(reader), opt); }
  public TDNode parse(CharSource src) { return parse(src, new TDJSONOption()); }
  public TDNode parse(CharSource src, TDJSONOption opt) { return parse(src, opt, new TreeDoc(opt.uri).getRoot()); }

  public TDNode parseAll(Reader reader) { return parseAll(reader, new TDJSONOption()); }
  public TDNode parseAll(Reader reader, TDJSONOption opt) { return parseAll(new ReaderCharSource(reader), opt); }
  public TDNode parseAll(CharSource src) { return parseAll(src, new TDJSONOption()); }
  public TDNode parseAll(String str, TDJSONOption opt) { return parseAll(new ArrayCharSource(str), opt); }
  /** Parse all the JSON objects in the input stream until EOF and store them inside an root node with array type */
  public TDNode parseAll(CharSource src, TDJSONOption opt) {
    TreeDoc doc = TreeDoc.ofArray();
    int docId = 0;
    while(src.skipSpacesAndReturnsAndCommas())
      TDJSONParser.get().parse(src, new TDJSONOption().setDocId(docId++), doc.getRoot().createChild());
    return doc.getRoot();
  }

  public TDNode parse(CharSource src, TDJSONOption opt, TDNode node) { return parse(src, opt, node, true); }

  public TDNode parse(CharSource src, TDJSONOption opt, TDNode node, boolean isRoot) {
    char c = skipSpaceAndComments(src);
    if (c == EOF)
      return node;

    node.setStart(src.getBookmark());
    try {
      if (c == '{')
        return parseMap(src, opt, node, true);

      if (c == '[')
        return parseArray(src, opt, node, true);

      if (isRoot) {
        switch (opt.defaultRootType) {
          case MAP:
            return parseMap(src, opt, node, false);
          case ARRAY:
            return parseArray(src, opt, node, false);
          default:;
        }
      }

      if(c == '"' || c == '\'' || c == '`') {
        src.skip();
        StringBuilder sb = new StringBuilder();
        src.readQuotedString(sb, c);
        readContinuousString(src, sb);
        return node.setValue(sb.toString());
      }

      String term = opt.termValue;
      if (node.getParent() != null)  // parent.type can either by ARRAY or MAP.
        term = node.getParent().getType() == TDNode.Type.ARRAY ? opt.termValueInArray : opt.termValueInMap;

      String str = src.readUntil(term, opt.termValueStrs).trim();
      return node.setValue(ClassUtil.toSimpleObject(str));
    } finally {
      node.setEnd(src.getBookmark());
    }
  }

  void readContinuousString(CharSource src, StringBuilder sb) {
    char c;
    while((c = skipSpaceAndComments(src)) != EOF) {
      if ("\"`'".indexOf(c) < 0)
        break;
      src.skip();
      src.readQuotedString(sb, c);
    }
  }

  /**
   * @return char next char to read (peeked), if '\uFFFF' indicate it's EOF
   */
  static char skipSpaceAndComments(CharSource src) {
    while (src.skipSpacesAndReturns()) {
      char c = src.peek();
      if (c == '#') {
        if (src.skipUntil("\n"))
          src.skip(1);
        continue;
      }

      if (c != '/' || src.isEof(1))
        return c;
      char c1 = src.peek(1);
      switch (c1) {
        case '/':   // line comments
          if (src.skipUntil("\n"))
            src.skip(1);
          break;
        case '*':   // block comments
          src.skip(2);
          src.skipUntilMatch("*/", true);
          break;
        default:
          return c1;
      }
    }
    return EOF;
  }

  TDNode parseMap(CharSource src, TDJSONOption opt, TDNode node, boolean withStartBracket) {
    node.setType(TDNode.Type.MAP);
    if (withStartBracket)
      src.skip();
    for (int i = 0;;) {
      char c = skipSpaceAndComments(src);
      if (c == EOF) {
        if (withStartBracket)
          throw src.createParseRuntimeException("EOF while expecting matching '}' with '{' at " + node.getStart());
        break;
      }

      if (c == '}') {
        src.skip();
        break;
      }

      if (src.startsWith(opt.deliminatorValue)) { // Skip ,
        src.skip(opt.deliminatorValue.length());
        continue;
      }

      String key;
      if (c == '"' || c == '\'' || c == '`') {
        src.skip();
        key = src.readQuotedString(c);
        c = skipSpaceAndComments(src);
//        if (c == EOF)
//          break;
        if (!src.startsWith(opt.deliminatorKey) && c != '{' && c != '[' && c != ',' && c != '}')
          throw src.createParseRuntimeException("No '" + opt.deliminatorKey + "' after key:" + key);
      } else {
        key = src.readUntil(opt.termKey, opt.termKeyStrs, 1, Integer.MAX_VALUE).trim();
        if (src.isEof())
          throw src.createParseRuntimeException("No '" + opt.deliminatorKey + "' after key:" + key);
        c = src.peek();
      }
      if (src.startsWith(opt.deliminatorKey))
        src.skip(opt.deliminatorKey.length());

      if (src.startsWith(opt.deliminatorValue) || c == '}')  // If there's no ':', we consider it as indexed value (array)
        node.createChild(i + "").setValue(key);
      else {
        TDNode childNode = parse(src, opt, node.createChild(key), false);
        if (opt.KEY_ID.equals(key) && childNode.getType() == TDNode.Type.SIMPLE) {
          String id = childNode.getValue().toString();
          if (opt.getDocId() != null) {
            id += "_" + opt.getDocId();
            childNode.setValue(id);
          }
          node.getDoc().getIdMap().put(id, node);
        } else if (TDNode.REF_KEY.equals(key) && childNode.getType() == TDNode.Type.SIMPLE) {
          if (opt.getDocId() != null)
            childNode.setValue(childNode.getValue() + "_" + opt.getDocId());
        }
      }
      i++;
    }
    return node;
  }

  TDNode parseArray(CharSource src, TDJSONOption opt, TDNode node, boolean withStartBracket) {
    node.setType(TDNode.Type.ARRAY);
    if (withStartBracket)
      src.skip();
    while (true) {
      char c = skipSpaceAndComments(src);
      if (c == EOF) {
        if (withStartBracket)
          throw src.createParseRuntimeException("EOF while expecting matching ']' with '[' at " + node.getStart());
        break;
      }

      if (c == ']') {
        src.skip();
        break;
      }

      parse(src, opt, node.createChild(null), false);
      c = skipSpaceAndComments(src);
      if (src.startsWith(opt.deliminatorValue)) {
        src.skip(opt.deliminatorValue.length());
      }
    }
    return node;
  }
}
