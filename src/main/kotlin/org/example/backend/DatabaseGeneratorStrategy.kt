package org.example.backend

import org.jooq.codegen.DefaultGeneratorStrategy

// https://www.jooq.org/doc/latest/manual/code-generation/codegen-object-naming/codegen-generatorstrategy/

class DatabaseGeneratorStrategy: DefaultGeneratorStrategy() {

    override fun getJavaIdentifier(Definition definition): String {
        return definition.getOutputName();
    }
}