package org.autogui.exec;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainFinder extends ClassVisitor {
    protected Pattern namePattern;
    protected String className;
    protected boolean hasMain;
    protected boolean nameMatched;
    protected int nameMatchedScore;

    public MainFinder(String name) {
        this(getPatternFromString(name));
    }

    public MainFinder(Pattern namePattern) {
        super(Opcodes.ASM7);
        this.namePattern = namePattern;
    }

    public String matchClass(Path classFile) throws IOException {
        ClassReader r = new ClassReader(Files.newInputStream(classFile));
        r.accept(this, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (this.hasMain() && this.isNameMatched()) {
            return this.getClassName();
        }
        return null;
    }

    public boolean hasMain() {
        return hasMain;
    }

    public String getClassName() {
        return className;
    }

    public boolean isNameMatched() {
        return nameMatched;
    }

    public int getNameMatchedScore() {
        return nameMatchedScore;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        className = name.replace('/', '.');
        if (this.namePattern != null) {
            name = name.replace('$', '.')
                        .replace('/', '.');

            /*
            name = name.replace('$', '/');

            int dot = name.lastIndexOf('/');
            String lastName = name;
            if (dot != -1) {
                lastName = name.substring(dot + 1);
            }
            */
            nameMatchedScore = match(name, namePattern);
            nameMatched = nameMatchedScore > 0;
        } else {
            nameMatchedScore = 1;
            nameMatched = true;
        }
    }

    /**
     *
     * @param name the tested string, no "$" or "/".
     * @param pattern  the matching pattern for entire name
     * @return 0 if the pattern did not matched to the entire name.
     *        if matched, then calculate score by matched groups.
     *         A group in the pattern
     *          1) matches a sub-sequence of an upper-case letter, then +4,
     *                e.g. "Hello": +4, or
     *          2) continuously matches a sub-sequence chars of the previous matched group, then +2,
     *                e.g. <code>".*?([Hh])[^.]*?([Ee])"</code> matches "(He)llo" +2.
     *          3) otherwise, +1.
     *        Also, add coverage-rate of camel-case words, up to +4 by {@link #scoreGroupCoverage(String, Matcher)}.
     */
    public static int match(String name, Pattern pattern) {
        Matcher m = pattern.matcher(name);
        if (m.matches()) {
            int score = 0;
            int prevStart = Integer.MIN_VALUE;
            for (int i = 0; i < m.groupCount(); ++i) {
                int pos = m.start(i + 1);
                String w = subSequenceIfUpperCase(name, pos);
                if (!w.isEmpty()) {
                    score += 4;
                } else if (prevStart + 1 == pos) {
                    score += 2;
                } else {
                    score += 1;
                }
                prevStart = pos;
            }
            score += scoreGroupCoverage(name, m);
            return Math.max(1, score);
        } else {
            return 0;
        }
    }

    public static String subSequenceIfUpperCase(String name, int pos) {
        if (pos < name.length()) {
            char c = name.charAt(pos);
            if (Character.isUpperCase(c)) {
                StringBuilder buf = new StringBuilder();
                buf.append(c);
                ++pos;
                while (pos < name.length()) {
                    char nc = name.charAt(pos);
                    if (Character.isUpperCase(nc) || Character.isDigit(nc) || nc == '.') {
                        break;
                    } else {
                        buf.append(nc);
                    }
                    ++pos;
                }
                return buf.toString();
            } else {
                return "";
            }
        } else {
            return "";
        }
    }

    static Pattern wordPattern = Pattern.compile("([A-Z][a-z]*)|([0-9]+)");

    /**
     *
     * @param name the tested string, no "$" or "/".
     * @param m entirely matching state.
     * @return if m.matches() returns false, then 0.
     *          otherwise, calculate group coverage by camel-case words;
     *             the words are matching ranges of the pattern <code>[A-Z][a-z]*|[0-9]+</code> to
     *                the last component of the name divided by ".".
     *              The score becomes
     *                 the size of the set of the matching words / the number of words of the last name,
     *                   as int.
     */
    public static int scoreGroupCoverage(String name, Matcher m) {
        if (m.matches()) {
            int lastDotNext = name.lastIndexOf('.') + 1;
            String clsName = name.substring(lastDotNext);
            Matcher wm = wordPattern.matcher(clsName);
            List<int[]> wordRanges = new ArrayList<>(); //{groupIndex, start, endExclusive}
            int g = 0;
            while (wm.find()) {
                wordRanges.add(new int[] {g, wm.start(0), wm.end(0)});
                ++g;
            }
            Set<Integer> found = new HashSet<>();
            for (int i = 0; i < m.groupCount(); ++i) {
                int gs = m.start(i);
                int fg = wordRanges.stream()
                        .filter(r -> r[1] <= gs && gs < r[2])
                        .findFirst()
                        .map(r -> r[0])
                        .orElse(-1);
                found.add(fg);
            }
            int matchedGroups = found.size();
            int totalGroups = wordRanges.size();
            return (int) (((double) matchedGroups / (double) totalGroups) * 4.0);
        } else {
            return 0;
        }
    }



    public Pattern getNamePattern() {
        return namePattern;
    }

    /**
     * If <i>str</i> contains ".", it regards as a sub-sequence of qualified name.
     *  The pattern becomes
     * <pre>
     *     ".*?(" + quote(<i>str</i>) + ")"
     * </pre>
     * Otherwise, it regards as prefix letters of CamelCase class name (excluding package names).
     *  The pattern becomes
     * <pre>
     *     ".*?" + prefixPat(<i>str</i>[0]) + "[^.]*?" + prefixPat(<i>str</i>[1]) ... + "[^.]*?"
     * </pre>
     *   where <code>prefixPat(c)</code> is the following conversion.
     * <pre>
     *     prefixPat(UpperCase <i>u</i>)   =&gt; "(<i>u</i>)"
     *     prefixPat(LowerCase <i>l</i>)   =&gt; "([" + <i>l</i> + upper(<i>l</i>) + "])"
     *     prefixPat("*")           =&gt; ".*?"
     * </pre>
     * @param str pattern string
     * @return compiled pattern of str
     */
    public static Pattern getPatternFromString(String str) {
        StringBuilder buf = new StringBuilder();
        buf.append(".*?");
        if (str.contains(".")) {
            buf.append("(");
            buf.append(Pattern.quote(str));
            buf.append(")");
        } else {
            for (char c : str.toCharArray()) {
                char upper = Character.toUpperCase(c);
                if (c == '*') {
                    buf.append(".*?");
                } else if (Character.isUpperCase(c) || upper == c) {
                    buf.append("(").append(c).append(")");
                } else {
                    buf.append("(").append("[").append(c).append(upper).append("]").append(")");
                }
                buf.append("[^\\.]*?");
            }
        }
        return Pattern.compile(buf.toString());
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        if (name.equals("main") &&
                ((access & Opcodes.ACC_STATIC) != 0) && ((access & Opcodes.ACC_PUBLIC) != 0) &&
                descriptor.equals("([Ljava/lang/String;)V")) {
            hasMain = true;
        }
        return super.visitMethod(access, name, descriptor, signature, exceptions);
    }
}
