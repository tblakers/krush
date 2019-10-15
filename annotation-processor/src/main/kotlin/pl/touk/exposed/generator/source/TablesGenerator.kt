package pl.touk.exposed.generator.source

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import pl.touk.exposed.generator.model.AssociationDefinition
import pl.touk.exposed.generator.model.AssociationType
import pl.touk.exposed.generator.model.EntityGraph
import pl.touk.exposed.generator.model.EntityGraphs
import pl.touk.exposed.generator.model.IdDefinition
import pl.touk.exposed.generator.model.IdType
import pl.touk.exposed.generator.model.PropertyDefinition
import pl.touk.exposed.generator.model.PropertyType
import pl.touk.exposed.generator.model.allAssociations
import pl.touk.exposed.generator.model.asObject
import pl.touk.exposed.generator.model.asVariable
import pl.touk.exposed.generator.model.packageName
import pl.touk.exposed.generator.model.traverse
import java.util.UUID

class TablesGenerator : SourceGenerator {

    override fun generate(graph: EntityGraph, graphs: EntityGraphs, packageName: String): FileSpec {
        val fileSpec = FileSpec.builder(packageName, fileName = "tables")
                .addImport("org.jetbrains.exposed.sql", "Table")

        graph.allAssociations().forEach { entity ->
            if (entity.packageName != packageName) {
                fileSpec.addImport(entity.packageName, "${entity.simpleName}Table")
            }
        }

        graph.traverse { entity ->
            val rootVal = entity.name.asVariable()

            val tableSpec = TypeSpec.objectBuilder("${entity.name}Table")
                    .superclass(Table::class)
                    .addSuperclassConstructorParameter(CodeBlock.of("%S", entity.table))

            entity.id?.let { id ->
                val name = id.name.toString()

                val columnType = Column::class.asTypeName().parameterizedBy(id.type.asTypeName() ?: id.typeMirror.asTypeName())
                val idSpec = PropertySpec.builder(name, columnType)
                val builder = CodeBlock.builder()
                val initializer = createIdInitializer(id)
                builder.add(initializer)

                if (id.generatedValue) {
                    builder.add(CodeBlock.of(".autoIncrement()")) //TODO disable autoIncrement when id is varchar
                }
                idSpec.initializer(builder.build())
                tableSpec.addProperty(idSpec.build())
            }

            entity.properties.forEach { column ->
                val name = column.name.toString()
                val columnType = Column::class.asTypeName().parameterizedBy(column.type.asTypeName()?.copy(nullable =  column.nullable) ?: column.typeMirror.asTypeName())
                val propSpec = PropertySpec.builder(name, columnType)
                val initializer = createPropertyInitializer(column)

                propSpec.initializer(initializer)
                tableSpec.addProperty(propSpec.build())
            }

            entity.getAssociations(AssociationType.MANY_TO_ONE).forEach { assoc ->
                val name = assoc.name.toString()

                val columnType = assoc.targetId.type.asTypeName()
                CodeBlock.builder()
                val initializer = createAssociationInitializer(assoc, name)
                tableSpec.addProperty(
                        PropertySpec.builder(name, Column::class.asClassName().parameterizedBy(columnType?.copy(nullable = true) ?: UUID::class.java.asTypeName()))
                                .initializer(initializer)
                                .build()
                )
            }

            entity.getAssociations(AssociationType.ONE_TO_ONE).filter {it.mapped}.forEach {assoc ->
                val name = assoc.name.toString()

                val columnType = assoc.targetId.type.asTypeName()
                CodeBlock.builder()
                val initializer = createAssociationInitializer(assoc, name)
                tableSpec.addProperty(
                        PropertySpec.builder(name, Column::class.asClassName().parameterizedBy(columnType?.copy(nullable = true) ?: UUID::class.java.asTypeName()))
                                .initializer(initializer)
                                .build()
                )
            }

            fileSpec.addType(tableSpec.build())

            entity.getAssociations(AssociationType.MANY_TO_MANY).forEach { assoc ->
                val targetVal = assoc.target.simpleName.asVariable()
                val targetTable = "${assoc.target.simpleName}Table"
                val manyToManyTableName = "${entity.name}${assoc.name.asObject()}Table"
                val manyToManyTableSpec = TypeSpec.objectBuilder(manyToManyTableName)
                        .superclass(Table::class)
                        .addSuperclassConstructorParameter(CodeBlock.of("%S", assoc.joinTable))

                manyToManyTableSpec.addProperty(
                        PropertySpec.builder("${rootVal}Id", Column::class.java.parameterizedBy(Long::class.java))
                                .initializer("long(\"${rootVal}_id\").references(${entity.tableName}.id)")
                                .build()
                )
                manyToManyTableSpec.addProperty(
                        PropertySpec.builder("${targetVal}Id", Column::class.java.parameterizedBy(Long::class.java))
                                .initializer("long(\"${targetVal}_id\").references($targetTable.id)")
                                .build()
                )

                fileSpec.addType(manyToManyTableSpec.build())
            }
        }

        return fileSpec.build()
    }

    private fun createIdInitializer(id: IdDefinition) : CodeBlock {
        return when (id.type) {
            IdType.STRING ->  CodeBlock.of("varchar(%S, %L)", id.columnName, id.annotation?.length ?: 255)
            IdType.LONG -> CodeBlock.of("long(%S)", id.columnName)
            IdType.INTEGER -> CodeBlock.of("integer(%S)", id.columnName)
            IdType.UUID -> CodeBlock.of("uuid(%S)", id.columnName)
            IdType.SHORT -> CodeBlock.of("short(%S)", id.columnName)
        }
    }

    private fun createPropertyInitializer(property: PropertyDefinition) : CodeBlock {
        val codeBlockBuilder = CodeBlock.builder()

        when (property.type) {
            PropertyType.STRING -> codeBlockBuilder.add(CodeBlock.of("varchar(%S, %L)", property.columnName, property.annotation?.length))
            PropertyType.LONG -> codeBlockBuilder.add(CodeBlock.of("long(%S)", property.columnName))
            PropertyType.BOOL -> codeBlockBuilder.add(CodeBlock.of("bool(%S)", property.columnName))
            PropertyType.DATE -> codeBlockBuilder.add(CodeBlock.of("date(%S)", property.columnName))
            PropertyType.DATETIME -> codeBlockBuilder.add(CodeBlock.of("datetime(%S)", property.columnName))
            PropertyType.UUID -> codeBlockBuilder.add(CodeBlock.of("uuid(%S)", property.columnName))
        }

        if (property.nullable) {
            codeBlockBuilder.add(".nullable()")
        }

        return codeBlockBuilder.build()
    }

    private fun createAssociationInitializer(association: AssociationDefinition, idName: String) : CodeBlock {
        val columnName = association.joinColumn ?: "${idName}_${association.targetId.name.asVariable()}"
        val targetTable = "${association.target.simpleName}Table"

        val codeBlockBuilder = CodeBlock.builder()
        when (association.targetId.type) {
            IdType.STRING -> codeBlockBuilder.add(CodeBlock.of("varchar(%S, %L)", columnName, 255)) //todo read length from annotation
            IdType.LONG -> codeBlockBuilder.add(CodeBlock.of("long(%S)", columnName))
            IdType.INTEGER -> codeBlockBuilder.add(CodeBlock.of("integer(%S)", columnName))
            IdType.UUID -> codeBlockBuilder.add(CodeBlock.of("uuid(%S)", columnName))
            IdType.SHORT -> codeBlockBuilder.add(CodeBlock.of("short(%S)", columnName))
        }

        return codeBlockBuilder.add(".references(%L).nullable()", "$targetTable.${association.targetId.name.asVariable()}").build()
    }
}

private fun PropertyType.asTypeName(): TypeName? {
    return when (this) {
        PropertyType.STRING -> STRING
        PropertyType.LONG -> LONG
        PropertyType.BOOL -> BOOLEAN
        else -> null
    }
}

fun IdType.asTypeName(): TypeName? {
    return when (this) {
        IdType.LONG -> com.squareup.kotlinpoet.LONG
        IdType.STRING -> com.squareup.kotlinpoet.STRING
        IdType.INTEGER -> com.squareup.kotlinpoet.INT
        IdType.SHORT -> com.squareup.kotlinpoet.SHORT
        else -> null
    }
}
