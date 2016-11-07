package monto.service.java8;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.configuration.BooleanOption;
import monto.service.configuration.Configuration;
import monto.service.configuration.NumberOption;
import monto.service.configuration.NumberSetting;
import monto.service.configuration.Option;
import monto.service.configuration.OptionGroup;
import monto.service.configuration.Setting;
import monto.service.gson.GsonMonto;
import monto.service.highlighting.Token;
import monto.service.highlighting.TokenCategory;
import monto.service.java8.antlr.Java8Lexer;
import monto.service.product.Products;
import monto.service.registration.ProductDescription;
import monto.service.registration.SourceDependency;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.apache.commons.lang3.text.WordUtils;

@SuppressWarnings("rawtypes")
public class JavaHighlighter extends MontoService {

  private Java8Lexer lexer = new Java8Lexer(new ANTLRInputStream());
  private static List<Option> options;

  static {
    options = new ArrayList<>();

    for (TokenCategory cat : TokenCategory.values()) {
      options.add(
          new OptionGroup(
              WordUtils.capitalize(cat.toString().toLowerCase()),
              new NumberOption(cat.toString() + "-red", "red", cat.getColor().getRed(), 0, 255),
              new NumberOption(
                  cat.toString() + "-green", "green", cat.getColor().getGreen(), 0, 255),
              new NumberOption(cat.toString() + "-blue", "blue", cat.getColor().getBlue(), 0, 255),
              new BooleanOption(cat.toString() + "-bold", "bold", false),
              new BooleanOption(cat.toString() + "-italic", "italic", false)));
    }
  }

  public JavaHighlighter(ZMQConfiguration zmqConfig) {
    super(
        zmqConfig,
        JavaServices.HIGHLIGHTER,
        "Java Syntax Highlighter",
        "A syntax highlighting service for Java that uses ANTLR for tokenizing",
        productDescriptions(new ProductDescription(Products.TOKENS, Languages.JAVA)),
        options,
        dependencies(new SourceDependency(Languages.JAVA)),
        commands());
  }

  @Override
  public void onRequest(Request request) throws IOException {
    SourceMessage version =
        request
            .getSourceMessage()
            .orElseThrow(() -> new IllegalArgumentException("No version message in request"));

    long start = System.nanoTime();
    lexer.setInputStream(new ANTLRInputStream(version.getContents()));
    List<Token> tokens =
        lexer
            .getAllTokens()
            .stream()
            .map(token -> convertToken(token))
            .collect(Collectors.toList());
    long end = System.nanoTime();

    sendProductMessage(
        version.getId(),
        version.getSource(),
        Products.TOKENS,
        Languages.JAVA,
        GsonMonto.toJsonTree(tokens),
        end - start);
  }

  @Override
  public void onConfigurationMessage(Configuration message) throws Exception {
    for (Setting setting : message.getSettings()) {
      String[] optionId = setting.getOptionId().split("-");
      String category = optionId[0];
      String option = optionId[1];
      TokenCategory tokCat = TokenCategory.valueOf(category);
      switch (option) {
        case "red":
          tokCat.setColor(tokCat.getColor().setRed(colorValue(setting)));
        case "green":
          tokCat.setColor(tokCat.getColor().setGreen(colorValue(setting)));
        case "blue":
          tokCat.setColor(tokCat.getColor().setBlue(colorValue(setting)));
        case "bold":
          tokCat.setStyle("bold");
        case "italic":
          tokCat.setStyle("italic");
          break;
      }
    }
  }

  public int colorValue(Setting setting) {
    return ((NumberSetting) setting).getValue().intValue();
  }

  private Token convertToken(org.antlr.v4.runtime.Token token) {

    TokenCategory category;
    switch (token.getType()) {
      case Java8Lexer.COMMENT:
      case Java8Lexer.LINE_COMMENT:
        category = TokenCategory.COMMENT;
        break;

      case Java8Lexer.CONST:
      case Java8Lexer.NullLiteral:
        category = TokenCategory.CONSTANT;
        break;

      case Java8Lexer.StringLiteral:
        category = TokenCategory.STRING;
        break;

      case Java8Lexer.CharacterLiteral:
        category = TokenCategory.CHARACTER;
        break;

      case Java8Lexer.IntegerLiteral:
        category = TokenCategory.NUMBER;
        break;

      case Java8Lexer.BooleanLiteral:
        category = TokenCategory.BOOLEAN;
        break;

      case Java8Lexer.FloatingPointLiteral:
        category = TokenCategory.FLOAT;
        break;

      case Java8Lexer.Identifier:
        category = TokenCategory.IDENTIFIER;
        break;

      case Java8Lexer.IF:
      case Java8Lexer.ELSE:
      case Java8Lexer.SWITCH:
        category = TokenCategory.CONDITIONAL;
        break;

      case Java8Lexer.FOR:
      case Java8Lexer.DO:
      case Java8Lexer.WHILE:
      case Java8Lexer.CONTINUE:
      case Java8Lexer.BREAK:
        category = TokenCategory.REPEAT;
        break;

      case Java8Lexer.CASE:
      case Java8Lexer.DEFAULT:
        category = TokenCategory.LABEL;
        break;

      case Java8Lexer.ADD:
      case Java8Lexer.ADD_ASSIGN:
      case Java8Lexer.SUB:
      case Java8Lexer.SUB_ASSIGN:
      case Java8Lexer.MUL:
      case Java8Lexer.MUL_ASSIGN:
      case Java8Lexer.DIV:
      case Java8Lexer.DIV_ASSIGN:
      case Java8Lexer.MOD:
      case Java8Lexer.MOD_ASSIGN:
      case Java8Lexer.INC:
      case Java8Lexer.DEC:
      case Java8Lexer.AND:
      case Java8Lexer.AND_ASSIGN:
      case Java8Lexer.BITAND:
      case Java8Lexer.OR:
      case Java8Lexer.OR_ASSIGN:
      case Java8Lexer.BITOR:
      case Java8Lexer.CARET:
      case Java8Lexer.XOR_ASSIGN:
      case Java8Lexer.GT:
      case Java8Lexer.GE:
      case Java8Lexer.LT:
      case Java8Lexer.LE:
      case Java8Lexer.TILDE:
      case Java8Lexer.LSHIFT_ASSIGN:
      case Java8Lexer.RSHIFT_ASSIGN:
      case Java8Lexer.URSHIFT_ASSIGN:
      case Java8Lexer.ASSIGN:
      case Java8Lexer.BANG:
      case Java8Lexer.EQUAL:
      case Java8Lexer.NOTEQUAL:
      case Java8Lexer.QUESTION:
      case Java8Lexer.COLON:
      case Java8Lexer.INSTANCEOF:
        category = TokenCategory.OPERATOR;
        break;

      case Java8Lexer.TRY:
      case Java8Lexer.CATCH:
      case Java8Lexer.THROW:
      case Java8Lexer.THROWS:
      case Java8Lexer.FINALLY:
        category = TokenCategory.EXCEPTION;
        break;

      case Java8Lexer.BOOLEAN:
      case Java8Lexer.BYTE:
      case Java8Lexer.CHAR:
      case Java8Lexer.DOUBLE:
      case Java8Lexer.FLOAT:
      case Java8Lexer.INT:
      case Java8Lexer.LONG:
      case Java8Lexer.SHORT:
      case Java8Lexer.VOID:
        category = TokenCategory.TYPE;
        break;

      case Java8Lexer.ABSTRACT:
      case Java8Lexer.PRIVATE:
      case Java8Lexer.PROTECTED:
      case Java8Lexer.PUBLIC:
      case Java8Lexer.STATIC:
      case Java8Lexer.SYNCHRONIZED:
      case Java8Lexer.VOLATILE:
      case Java8Lexer.FINAL:
      case Java8Lexer.TRANSIENT:
      case Java8Lexer.NATIVE:
      case Java8Lexer.STRICTFP:
        category = TokenCategory.MODIFIER;
        break;

      case Java8Lexer.CLASS:
      case Java8Lexer.ENUM:
      case Java8Lexer.INTERFACE:
        category = TokenCategory.STRUCTURE;
        break;

      case Java8Lexer.EXTENDS:
      case Java8Lexer.IMPLEMENTS:
      case Java8Lexer.IMPORT:
      case Java8Lexer.PACKAGE:
      case Java8Lexer.THIS:
      case Java8Lexer.SUPER:
      case Java8Lexer.GOTO:
      case Java8Lexer.NEW:
      case Java8Lexer.RETURN:
      case Java8Lexer.ASSERT:
        category = TokenCategory.KEYWORD;
        break;

      case Java8Lexer.LPAREN:
      case Java8Lexer.RPAREN:
      case Java8Lexer.LBRACE:
      case Java8Lexer.RBRACE:
      case Java8Lexer.LBRACK:
      case Java8Lexer.RBRACK:
        category = TokenCategory.PARENTHESIS;
        break;

      case Java8Lexer.COMMA:
      case Java8Lexer.SEMI:
      case Java8Lexer.DOT:
      case Java8Lexer.ELLIPSIS:
      case Java8Lexer.ARROW:
      case Java8Lexer.COLONCOLON:
        category = TokenCategory.DELIMITER;
        break;

      case Java8Lexer.AT:
        category = TokenCategory.META;
        break;

      case Java8Lexer.WS:
        category = TokenCategory.WHITESPACE;
        break;

      default:
        category = TokenCategory.UNKNOWN;
    }

    int offset = token.getStartIndex();
    int length = token.getStopIndex() - offset + 1;
    return new Token(offset, length, category.getFont());
  }
}
