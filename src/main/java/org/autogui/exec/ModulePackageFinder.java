package org.autogui.exec;

import java.io.IOException;
import java.lang.classfile.Attribute;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleExportInfo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ModulePackageFinder {
    protected String name;
    protected List<String> exports = new ArrayList<>();

    public String getName() {
        return name;
    }

    public List<String> getExports() {
        return exports;
    }

    public boolean process(Path classFile) throws IOException {
        ClassModel model = ClassFile.of().parse(classFile);
        if (model.isModuleInfo()) {
            model.attributes()
                    .forEach(this::processAttribute);
            return name != null;
        } else {
            return false;
        }
    }

    protected void processAttribute(Attribute<?> attr) {
        if (attr instanceof ModuleAttribute mod) {
            this.name = mod.moduleName().name().toString();
            mod.exports()
                    .forEach(this::processExports);
        }
    }

    protected void processExports(ModuleExportInfo exp) {
        var pack = exp.exportedPackage()
                .name().toString();
        exports.add(fromInternalName(pack));
    }

    protected String fromInternalName(String internalName) {
        return internalName.replace('/', '.');
    }
}
