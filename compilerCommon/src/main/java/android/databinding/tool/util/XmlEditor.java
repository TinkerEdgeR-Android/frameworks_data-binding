/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.tool.util;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import android.databinding.parser.BindingExpressionLexer;
import android.databinding.parser.BindingExpressionParser;
import android.databinding.parser.XMLLexer;
import android.databinding.parser.XMLParser;
import android.databinding.parser.XMLParser.AttributeContext;
import android.databinding.parser.XMLParser.ElementContext;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Ugly inefficient class to strip unwanted tags from XML.
 * Band-aid solution to unblock development
 */
public class XmlEditor {

    public static String strip(File f, String newTag) throws IOException {
        ANTLRInputStream inputStream = new ANTLRInputStream(new FileReader(f));
        XMLLexer lexer = new XMLLexer(inputStream);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        XMLParser parser = new XMLParser(tokenStream);
        XMLParser.DocumentContext expr = parser.document();
        XMLParser.ElementContext root = expr.element();

        if (root == null || !"layout".equals(nodeName(root))) {
            return null; // not a binding layout
        }

        Iterable<? extends XMLParser.ElementContext> dataNodes = Iterables
                .filter(elements(root), new Predicate<XMLParser.ElementContext>() {
                    @Override
                    public boolean apply(XMLParser.ElementContext input) {
                        return "data".equals(nodeName(input));
                    }
                });
        Preconditions.checkState(Iterables.size(dataNodes) < 2,
                "Multiple binding data tags. Expecting a maximum of one.");

        ArrayList<String> lines = Lists.newArrayList();
        lines.addAll(FileUtils.readLines(f, "utf-8"));

        for (android.databinding.parser.XMLParser.ElementContext it : dataNodes) {
            replace(lines, toPosition(it.getStart()), toEndPosition(it.getStop()), "");
        }
        Iterable<? extends XMLParser.ElementContext> layoutNodes = Iterables
                .filter(elements(root), new Predicate<XMLParser.ElementContext>() {
                    @Override
                    public boolean apply(XMLParser.ElementContext input) {
                        return !"data".equals(nodeName(input));
                    }
                });
        Preconditions.checkState(Iterables.size(layoutNodes) == 1,
                "Only one layout element and one data element are allowed");

        final XMLParser.ElementContext layoutNode = Iterables.getFirst(layoutNodes, null);

        ArrayList<Pair<String, android.databinding.parser.XMLParser.ElementContext>> noTag = Lists
                .newArrayList();

        recurseReplace(layoutNode, lines, noTag, newTag, 0);

        // Remove the <layout>
        Position rootStartTag = toPosition(root.getStart());
        Position rootEndTag = toPosition(root.content().getStart());
        replace(lines, rootStartTag, rootEndTag, "");

        // Remove the </layout>
        ImmutablePair<Position, Position> endLayoutPositions = findTerminalPositions(root, lines);
        replace(lines, endLayoutPositions.left, endLayoutPositions.right, "");

        StringBuilder rootAttributes = new StringBuilder();
        for (AttributeContext attr : attributes(root)) {
            rootAttributes.append(' ').append(attr.getText());
        }
        Optional<Pair<String, XMLParser.ElementContext>> pairOptional = Iterables
                .tryFind(noTag, new Predicate<Pair<String, XMLParser.ElementContext>>() {
                    @Override
                    public boolean apply(Pair<String, XMLParser.ElementContext> input) {
                        return input.getRight() == layoutNode;
                    }
                });
        if (pairOptional.isPresent()) {
            Pair<String, XMLParser.ElementContext> noTagRoot = pairOptional.get();
            ImmutablePair<String, XMLParser.ElementContext>
                    newRootTag = new ImmutablePair<>(
                    noTagRoot.getLeft() + rootAttributes.toString(), layoutNode);
            int index = noTag.indexOf(noTagRoot);
            noTag.set(index, newRootTag);
        } else {
            ImmutablePair<String, XMLParser.ElementContext> newRootTag =
                    new ImmutablePair<>(rootAttributes.toString(), layoutNode);
            noTag.add(newRootTag);
        }
        //noinspection NullableProblems
        Collections.sort(noTag, new Comparator<Pair<String, XMLParser.ElementContext>>() {
            @Override
            public int compare(Pair<String, XMLParser.ElementContext> o1,
                    Pair<String, XMLParser.ElementContext> o2) {
                Position start1 = toPosition(o1.getRight().getStart());
                Position start2 = toPosition(o2.getRight().getStart());
                int lineCmp = Integer.compare(start2.line, start1.line);
                if (lineCmp != 0) {
                    return lineCmp;
                }
                return Integer.compare(start2.charIndex, start1.charIndex);
            }
        });
        for (Pair<String, android.databinding.parser.XMLParser.ElementContext> it : noTag) {
            XMLParser.ElementContext element = it.getRight();
            String tag = it.getLeft();
            Position endTagPosition = endTagPosition(element);
            fixPosition(lines, endTagPosition);
            String line = lines.get(endTagPosition.line);
            String newLine = line.substring(0, endTagPosition.charIndex) + " " + tag +
                    line.substring(endTagPosition.charIndex);
            lines.set(endTagPosition.line, newLine);
        }
        return StringUtils.join(lines, System.getProperty("line.separator"));
    }

    private static Position toPosition(Token token) {
        return new Position(token.getLine() - 1, token.getCharPositionInLine());
    }

    private static Position toEndPosition(Token token) {
        return new Position(token.getLine() - 1,
                token.getCharPositionInLine() + token.getText().length());
    }

    private static String nodeName(XMLParser.ElementContext elementContext) {
        return elementContext.elmName.getText();
    }

    private static List<? extends AttributeContext> attributes(XMLParser.ElementContext elementContext) {
        if (elementContext.attribute() == null) {
            return Lists.newArrayList();
        } else {
            return elementContext.attribute();
        }
    }

    private static Iterable<? extends AttributeContext> expressionAttributes(
            XMLParser.ElementContext elementContext) {
        return Iterables.filter(attributes(elementContext), new Predicate<AttributeContext>() {
            @Override
            public boolean apply(AttributeContext input) {
                String attrName = input.attrName.getText();
                String value = input.attrValue.getText();
                return attrName.equals("android:tag") ||
                        (value.startsWith("\"@{") && value.endsWith("}\"")) ||
                        (value.startsWith("'@{") && value.endsWith("}'"));
            }
        });
    }

    private static Position endTagPosition(XMLParser.ElementContext context) {
        if (context.content() == null) {
            // no content, so just subtract from the "/>"
            Position endTag = toEndPosition(context.getStop());
            Preconditions.checkState(endTag.charIndex > 0);
            endTag.charIndex -= 2;
            return endTag;
        } else {
            // tag with no attributes, but with content
            Position position = toPosition(context.content().getStart());
            Preconditions.checkState(position.charIndex > 0);
            position.charIndex--;
            return position;
        }
    }

    private static List<? extends android.databinding.parser.XMLParser.ElementContext> elements(
            XMLParser.ElementContext context) {
        if (context.content() != null && context.content().element() != null) {
            return context.content().element();
        }
        return Lists.newArrayList();
    }

    private static boolean replace(ArrayList<String> lines, Position start, Position end,
            String text) {
        fixPosition(lines, start);
        fixPosition(lines, end);
        if (start.line != end.line) {
            String startLine = lines.get(start.line);
            String newStartLine = startLine.substring(0, start.charIndex) + text;
            lines.set(start.line, newStartLine);
            for (int i = start.line + 1; i < end.line; i++) {
                String line = lines.get(i);
                lines.set(i, replaceWithSpaces(line, 0, line.length() - 1));
            }
            String endLine = lines.get(end.line);
            String newEndLine = replaceWithSpaces(endLine, 0, end.charIndex - 1);
            lines.set(end.line, newEndLine);
            return true;
        } else if (end.charIndex - start.charIndex >= text.length()) {
            String line = lines.get(start.line);
            int endTextIndex = start.charIndex + text.length();
            String replacedText = replaceRange(line, start.charIndex, endTextIndex, text);
            String spacedText = replaceWithSpaces(replacedText, endTextIndex, end.charIndex - 1);
            lines.set(start.line, spacedText);
            return true;
        } else {
            String line = lines.get(start.line);
            String newLine = replaceWithSpaces(line, start.charIndex, end.charIndex - 1);
            lines.set(start.line, newLine);
            return false;
        }
    }

    private static String replaceRange(String line, int start, int end, String newText) {
        return line.substring(0, start) + newText + line.substring(end);
    }

    private static boolean hasExpressionAttributes(XMLParser.ElementContext context) {
        Iterable<? extends AttributeContext> expressions = expressionAttributes(context);
        int size = Iterables.size(expressions);
        //noinspection ConstantConditions
        return size > 1 || (size == 1 &&
                !Iterables.getFirst(expressions, null).attrName.getText().equals("android:tag"));
    }

    private static int recurseReplace(XMLParser.ElementContext node, ArrayList<String> lines,
            ArrayList<Pair<String, XMLParser.ElementContext>> noTag,
            String newTag, int bindingIndex) {
        int nextBindingIndex = bindingIndex;
        boolean isMerge = "merge".equals(nodeName(node));
        final boolean containsInclude = Iterables.tryFind(elements(node),
                new Predicate<ElementContext>() {
                    @Override
                    public boolean apply(ElementContext input) {
                        return "include".equals(nodeName(input));
                    }
                }).isPresent();
        if (!isMerge && (hasExpressionAttributes(node) || newTag != null || containsInclude)) {
            String tag = "";
            if (newTag != null) {
                tag = "android:tag=\"" + newTag + "_" + bindingIndex + "\"";
                nextBindingIndex++;
            } else if (!"include".equals(nodeName(node))) {
                tag = "android:tag=\"binding_" + bindingIndex + "\"";
                nextBindingIndex++;
            }
            for (AttributeContext it : expressionAttributes(node)) {
                Position start = toPosition(it.getStart());
                Position end = toEndPosition(it.getStop());
                String defaultVal = defaultReplacement(it);
                if (defaultVal != null) {
                    replace(lines, start, end, it.attrName.getText() + "=\"" + defaultVal + "\"");
                } else if (replace(lines, start, end, tag)) {
                    tag = "";
                }
            }
            if (tag.length() != 0) {
                noTag.add(new ImmutablePair<>(tag, node));
            }
        }

        String nextTag;
        if (bindingIndex == 0 && isMerge) {
            nextTag = newTag;
        } else {
            nextTag = null;
        }
        for (XMLParser.ElementContext it : elements(node)) {
            nextBindingIndex = recurseReplace(it, lines, noTag, nextTag, nextBindingIndex);
        }
        return nextBindingIndex;
    }

    private static String defaultReplacement(XMLParser.AttributeContext attr) {
        String textWithQuotes = attr.attrValue.getText();
        String escapedText = textWithQuotes.substring(1, textWithQuotes.length() - 1);
        if (!escapedText.startsWith("@{") || !escapedText.endsWith("}")) {
            return null;
        }
        String text = StringEscapeUtils
                .unescapeXml(escapedText.substring(2, escapedText.length() - 1));
        ANTLRInputStream inputStream = new ANTLRInputStream(text);
        BindingExpressionLexer lexer = new BindingExpressionLexer(inputStream);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        BindingExpressionParser parser = new BindingExpressionParser(tokenStream);
        BindingExpressionParser.BindingSyntaxContext root = parser.bindingSyntax();
        BindingExpressionParser.DefaultsContext defaults = root.defaults();
        if (defaults != null) {
            BindingExpressionParser.ConstantValueContext constantValue = defaults
                    .constantValue();
            BindingExpressionParser.LiteralContext literal = constantValue.literal();
            if (literal != null) {
                BindingExpressionParser.StringLiteralContext stringLiteral = literal
                        .stringLiteral();
                if (stringLiteral != null) {
                    TerminalNode doubleQuote = stringLiteral.DoubleQuoteString();
                    if (doubleQuote != null) {
                        String quotedStr = doubleQuote.getText();
                        String unquoted = quotedStr.substring(1, quotedStr.length() - 1);
                        return StringEscapeUtils.escapeXml10(unquoted);
                    } else {
                        String quotedStr = stringLiteral.SingleQuoteString().getText();
                        String unquoted = quotedStr.substring(1, quotedStr.length() - 1);
                        String unescaped = unquoted.replace("\"", "\\\"").replace("\\`", "`");
                        return StringEscapeUtils.escapeXml10(unescaped);
                    }
                }
            }
            return constantValue.getText();
        }
        return null;
    }

    private static ImmutablePair<Position, Position> findTerminalPositions(
            XMLParser.ElementContext node,  ArrayList<String> lines) {
        Position endPosition = toEndPosition(node.getStop());
        Position startPosition = toPosition(node.getStop());
        int index;
        do {
            index = lines.get(startPosition.line).lastIndexOf("</");
            startPosition.line--;
        } while (index < 0);
        startPosition.line++;
        startPosition.charIndex = index;
        //noinspection unchecked
        return new ImmutablePair<>(startPosition, endPosition);
    }

    private static String replaceWithSpaces(String line, int start, int end) {
        StringBuilder lineBuilder = new StringBuilder(line);
        for (int i = start; i <= end; i++) {
            lineBuilder.setCharAt(i, ' ');
        }
        return lineBuilder.toString();
    }

    private static void fixPosition(ArrayList<String> lines, Position pos) {
        String line = lines.get(pos.line);
        while (pos.charIndex > line.length()) {
            pos.charIndex--;
        }
    }

    private static class Position {

        int line;
        int charIndex;

        public Position(int line, int charIndex) {
            this.line = line;
            this.charIndex = charIndex;
        }
    }

}
