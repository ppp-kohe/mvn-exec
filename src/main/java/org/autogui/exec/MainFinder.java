package org.autogui.exec;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public class MainFinder extends ClassVisitor {
    protected Pattern namePattern;
    protected String className;
    protected boolean hasMain;
    protected boolean nameMatched;

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
            nameMatched = namePattern.matcher(name).matches();
        } else {
            nameMatched = true;
        }
    }


    public Pattern getNamePattern() {
        return namePattern;
    }

    /**
     * If <i>str</i> contains ".", it regards as a sub-sequence of qualified name.
     *  The pattern becomes
     * <pre>
     *     ".*?" + quote(<i>str</i>)
     * </pre>
     * Otherwise, it regards as prefix letters of CamelCase class name (excluding package names).
     *  The pattern becomes
     * <pre>
     *     ".*?" + prefixPat(<i>str</i>[0]) + "[^.]*?" + prefixPat(<i>str</i>[1]) ... + "[^.]*?"
     * </pre>
     *   where <code>prefixPat(c)</code> is the following conversion.
     * <pre>
     *     prefixPat(UpperCase <i>u</i>)   =&gt; "<i>u</i>"
     *     prefixPat(LowerCase <i>l</i>)   =&gt; "[" + <i>l</i> + upper(<i>l</i>) + "]"
     *     prefixPat("*")           =&gt; ".*?"
     * </pre>
     * @param str pattern string
     * @return compiled pattern of str
     */
    public static Pattern getPatternFromString(String str) {
        StringBuilder buf = new StringBuilder();
        buf.append(".*?");
        if (str.contains(".")) {
            buf.append(Pattern.quote(str));
        } else {
            for (char c : str.toCharArray()) {
                char upper = Character.toUpperCase(c);
                if (c == '*') {
                    buf.append(".*?");
                } else if (Character.isUpperCase(c) || upper == c) {
                    buf.append(c);
                } else {
                    buf.append("[").append(c).append(upper).append("]");
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
