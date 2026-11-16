import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.type.UnknownType;
import com.github.javaparser.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Formatting step "Finalize parameters and locals" (was build-logic's FinalizeVariables.kt): marks
 * {@code final} every parameter, local variable and type pattern that is never reassigned.
 */
final class FinalizeVariables {

    private static final Set<UnaryExpr.Operator> MUTATING_OPERATORS = Set.of(
            UnaryExpr.Operator.PREFIX_INCREMENT,
            UnaryExpr.Operator.PREFIX_DECREMENT,
            UnaryExpr.Operator.POSTFIX_INCREMENT,
            UnaryExpr.Operator.POSTFIX_DECREMENT);

    private FinalizeVariables() {
    }

    @SuppressWarnings("unchecked") // javaparser's findAncestor(Class<N>...) varargs
    static String apply(final String source) {
        final ParseResult<CompilationUnit> result = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                .setAttributeComments(false)
                .setTabSize(1))
                .parse(source);
        final CompilationUnit unit = result.getResult()
                .orElseThrow(() -> new IllegalStateException(result.getProblems().toString()));

        final int[] lineStarts = lineStarts(source);
        final TreeSet<Integer> insertions = new TreeSet<>();

        for (final Parameter parameter : unit.findAll(Parameter.class)) {
            final Node scope = parameterScope(parameter);
            if (scope == null) {
                continue;
            }
            final String name = parameter.getNameAsString();
            if (!parameter.isFinal() && !name.equals("_") && !isReassigned(List.of(scope), name)) {
                insertions.add(insertionOffset(lineStarts, parameter, parameter.getType()));
            }
        }

        for (final VariableDeclarationExpr declaration : unit.findAll(VariableDeclarationExpr.class)) {
            final List<Node> scope = localScope(declaration);
            final List<String> names = declaration.getVariables().stream().map(VariableDeclarator::getNameAsString).toList();
            if (!declaration.isFinal() && !names.contains("_") && names.stream().noneMatch(n -> isReassigned(scope, n))) {
                insertions.add(insertionOffset(lineStarts, declaration, declaration.getElementType()));
            }
        }

        for (final TypePatternExpr pattern : unit.findAll(TypePatternExpr.class)) {
            final Optional<Node> entryScope = pattern.findAncestor(SwitchEntry.class)
                    .filter(entry -> entry.getLabels().stream().anyMatch(l -> l == pattern || l.isAncestorOf(pattern)))
                    .map(entry -> entry);
            final Node scope = entryScope.or(() -> pattern.findAncestor(BodyDeclaration.class).map(b -> b)).orElse(null);
            if (scope == null) {
                continue;
            }
            final String name = pattern.getNameAsString();
            if (!pattern.isFinal() && !name.equals("_") && !isReassigned(List.of(scope), name)) {
                insertions.add(insertionOffset(lineStarts, pattern, pattern.getType()));
            }
        }

        final StringBuilder builder = new StringBuilder(source);
        for (final int offset : insertions.descendingSet()) {
            builder.insert(offset, "final ");
        }
        return builder.toString();
    }

    /** Null for record components, bodiless methods and implicitly typed lambda parameters. */
    private static Node parameterScope(final Parameter parameter) {
        final Node owner = parameter.getParentNode().orElse(null);
        if (owner instanceof final MethodDeclaration method) {
            return method.getBody().orElse(null);
        } else if (owner instanceof final ConstructorDeclaration constructor) {
            return constructor.getBody();
        } else if (owner instanceof final LambdaExpr lambda) {
            return parameter.getType() instanceof UnknownType ? null : lambda.getBody();
        } else if (owner instanceof final CatchClause clause) {
            return clause.getBody();
        }
        return null;
    }

    private static List<Node> localScope(final VariableDeclarationExpr declaration) {
        final Node parent = declaration.getParentNode().orElse(null);
        if (parent == null) {
            return List.of();
        }
        if (!(parent instanceof ExpressionStmt)) {
            return List.of(parent);
        }
        final Node container = parent.getParentNode().orElse(null);
        if (container instanceof final BlockStmt block) {
            final List<Node> after = new ArrayList<>();
            boolean seen = false;
            for (final Statement statement : block.getStatements()) {
                seen |= statement == parent;
                if (seen) {
                    after.add(statement);
                }
            }
            return after;
        } else if (container instanceof SwitchEntry) {
            return container.getParentNode().<List<Node>>map(List::of).orElse(List.of());
        } else if (container == null) {
            return List.of();
        }
        return List.of(container);
    }

    private static boolean isReassigned(final List<Node> scope, final String name) {
        return scope.isEmpty() || scope.stream().anyMatch(node ->
                node.findFirst(AssignExpr.class, a -> targets(a.getTarget(), name)).isPresent()
                        || node.findFirst(UnaryExpr.class,
                        u -> MUTATING_OPERATORS.contains(u.getOperator()) && targets(u.getExpression(), name)).isPresent());
    }

    private static boolean targets(final Expression expression, final String name) {
        if (expression instanceof final NameExpr n) {
            return n.getNameAsString().equals(name);
        } else if (expression instanceof final EnclosedExpr e) {
            return targets(e.getInner(), name);
        }
        return false;
    }

    private static int insertionOffset(final int[] lineStarts, final Node declaration, final Node type) {
        final Position start = declaration.getBegin().orElseThrow();
        final Position typeStart = type.getBegin().orElseThrow();
        final Position position = typeStart.line > start.line ? typeStart : start;
        return lineStarts[position.line - 1] + position.column - 1;
    }

    private static int[] lineStarts(final String source) {
        final List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }
}
