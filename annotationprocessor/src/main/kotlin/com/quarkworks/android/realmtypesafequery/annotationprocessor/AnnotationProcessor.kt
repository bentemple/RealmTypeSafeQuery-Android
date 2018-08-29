package com.quarkworks.android.realmtypesafequery.annotationprocessor

import com.google.auto.service.AutoService
import com.quarkworks.android.realmtypesafequery.annotations.SkipGenerationOfRealmFieldName
import com.quarkworks.android.realmtypesafequery.annotations.SkipGenerationOfRealmField
import com.quarkworks.android.realmtypesafequery.annotations.GenerateRealmFieldNames
import com.quarkworks.android.realmtypesafequery.annotations.GenerateRealmFields
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

import java.io.IOException
import java.util.LinkedList

import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedSourceVersion
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.ElementFilter
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic

import io.realm.annotations.Ignore
import io.realm.annotations.Index
import io.realm.annotations.PrimaryKey
import javax.annotation.processing.SupportedOptions
import javax.tools.StandardLocation


@AutoService(Processor::class)
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedOptions(AnnotationProcessor.KAPT_KOTLIN_GENERATED_OPTION_NAME)
class AnnotationProcessor : AbstractProcessor() {

    companion object {
        const val KAPT_KOTLIN_GENERATED_OPTION_NAME = "kapt.kotlin.generated"
    }

    private fun String.toConstId() : String = this.replace("([a-z])([A-Z])".toRegex(), "$1_$2").toUpperCase()

    private fun Element.isAnnotatedWith(annotation: Class<out Annotation>) : Boolean =
            this.getAnnotation(annotation) != null

    private val propertySpecsModifiers = arrayOf(KModifier.PUBLIC)
    private val packageName: String = "com.quarkworks.android.realmtypesafequery" + ".generated"
    private val supportedAnnotationTypes: Set<String> = setOf(
            GenerateRealmFieldNames::class.java.canonicalName,
            GenerateRealmFields::class.java.canonicalName)

    private lateinit var realmModel: DeclaredType
    private lateinit var realmListErasure: TypeMirror
    private lateinit var typeUtils: Types
    private lateinit var elementUtils: Elements

    override fun getSupportedAnnotationTypes(): Set<String> = supportedAnnotationTypes

    @Synchronized
    override fun init(processingEnvironment: ProcessingEnvironment) {
        super.init(processingEnvironment)
        log("GIT_COMMIT:${GIT_COMMIT.`val`}")
        typeUtils = processingEnv.typeUtils
        elementUtils = processingEnv.elementUtils
        realmModel = elementUtils.getTypeElement("io.realm.RealmModel").asType() as DeclaredType
        realmListErasure = typeUtils.erasure(elementUtils.getTypeElement("io.realm.RealmList")
                .asType())
    }

    @Synchronized
    override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
        generateRealmFieldNames(roundEnv)
        generateRealmFields(roundEnv)
        return true
    }

    private fun generateRealmFieldNames(roundEnv: RoundEnvironment) {
        for (element in roundEnv.getElementsAnnotatedWith(GenerateRealmFieldNames::class.java)) {
            if (element !is TypeElement) continue

            val variableElements = ElementFilter.fieldsIn(element.enclosedElements)
            val propertySpecs = LinkedList<PropertySpec>()

            for (realmField in variableElements) {
                // ignore static and @Ignore fields
                if (realmField.modifiers.contains(Modifier.STATIC)) continue
                if (realmField.isAnnotatedWith(Ignore::class.java)) continue
                if (realmField.isAnnotatedWith(SkipGenerationOfRealmFieldName::class.java)) continue

                val name = realmField.simpleName.toString().toConstId()

                val propertySpec = PropertySpec.builder(name, String::class, KModifier.CONST, *propertySpecsModifiers)
                        .initializer("%S", realmField.simpleName)
                        .build()

                propertySpecs.add(propertySpec)
            }

            val className = element.simpleName.toString() + "FieldNames"

            val typeSpec = TypeSpec.objectBuilder(className).addProperties(propertySpecs)
                    .build()

            val kotlinFile = FileSpec.builder(packageName, className)
                .addType(typeSpec)
                .build()

            try {
                val kotlinFileObject = processingEnv.filer.createResource(StandardLocation.SOURCE_OUTPUT,
                    packageName, "$className.kt")
                val writer = kotlinFileObject.openWriter()
                kotlinFile.writeTo(writer)
                writer.close()
            } catch (e: IOException) {
                this.reportError(element, e.toString())
            }
        }
    }

    private fun makePropertySpec(realmClassElement: Element, realmFieldElement: Element): PropertySpec {
        
        if (typeUtils.isSubtype(realmFieldElement.asType(), realmModel)) {
            return makeToOne(realmClassElement, realmFieldElement)
        }
        if (typeUtils.isSubtype(typeUtils.erasure(realmFieldElement.asType()), realmListErasure)) {
            return makeToMany(realmClassElement, realmFieldElement)
        }
        val rfeClass = realmFieldElement.asType().toString()
        val fieldName = realmFieldElement.simpleName.toString()

        val isPrimaryKey = realmFieldElement.isAnnotatedWith(PrimaryKey::class.java)
        val isIndex = realmFieldElement.isAnnotatedWith(Index::class.java)
        val typeName = realmClassElement.asType().asTypeName()
        val parameterizedTypeName: ParameterizedTypeName

        parameterizedTypeName = if (!isPrimaryKey && !isIndex) {
            Maps.BASE_MAP[rfeClass]!!.parameterizedBy(typeName)
        } else {
            Maps.INDEX_MAP[Maps.BASE_MAP[rfeClass]]!!.parameterizedBy(typeName)
        }

        return PropertySpec.builder(fieldName.toConstId(), parameterizedTypeName, *propertySpecsModifiers)
            .initializer("%T(%T::class.java, %S)", parameterizedTypeName, typeName, fieldName)
            .addAnnotation(JvmField::class)
            .build()
    }

    private fun makeToMany(realmClassElement: Element, realmFieldElement: Element): PropertySpec {
        val typeMirror = realmClassElement.asType()
        val fieldName = realmFieldElement.simpleName.toString()

        val parameterizedTypeName = Maps.REALM_TO_MANY_RELATIONSHIP.parameterizedBy(
                typeMirror.asTypeName(),
                (realmFieldElement.asType() as DeclaredType).typeArguments[0].asTypeName())

        return PropertySpec.builder(fieldName.toConstId(), parameterizedTypeName, *propertySpecsModifiers)
            .initializer("%T(%T::class.java, %S)", parameterizedTypeName, typeMirror.asTypeName(), fieldName)
            .addAnnotation(JvmField::class)
            .build()

    }

    private fun makeToOne(realmClassElement: Element, realmFieldElement: Element): PropertySpec {
        val typeMirror = realmClassElement.asType()
        val fieldName = realmFieldElement.simpleName.toString()

        val parameterizedTypeName = Maps.REALM_TO_ONE_RELATIONSHIP.parameterizedBy(
                typeMirror.asTypeName(),
                realmFieldElement.asType().asTypeName())

        return PropertySpec.builder(fieldName.toConstId(), parameterizedTypeName, *propertySpecsModifiers)
            .initializer("%T(%T::class.java, %S)", parameterizedTypeName, typeMirror.asTypeName(), fieldName)
            .addAnnotation(JvmField::class)
            .build()

    }

    private fun generateRealmFields(roundEnv: RoundEnvironment) {
        for (element in roundEnv.getElementsAnnotatedWith(GenerateRealmFields::class.java)) {
            if (element !is TypeElement) continue

            val variableElements = ElementFilter.fieldsIn(element.enclosedElements)
            val propertySpecs = LinkedList<PropertySpec>()

            for (realmField in variableElements) {
                if (realmField.modifiers.contains(Modifier.STATIC)) continue
                if (realmField.isAnnotatedWith(Ignore::class.java)) continue
                if (realmField.isAnnotatedWith(SkipGenerationOfRealmField::class.java)) continue

                propertySpecs.add(makePropertySpec(element, realmField))
            }

            val className = element.simpleName.toString() + "Fields"

            val typeSpec = TypeSpec.objectBuilder(className)
                    .addProperties(propertySpecs)
                    .addModifiers(KModifier.PUBLIC)
                    .build()

            val kotlinFile = FileSpec.builder(packageName, className)
                .addType(typeSpec)
                .build()

            try {
                val kotlinFileObject = processingEnv.filer.createResource(StandardLocation.SOURCE_OUTPUT, packageName, "$className.kt")
                val writer = kotlinFileObject.openWriter()
                kotlinFile.writeTo(writer)
                writer.close()
            } catch (e: IOException) {
                this.reportError(element, e.toString())
            }
        }
    }


    private fun reportError(element: Element, message: CharSequence) {
        this.processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, message, element)
    }

    private fun reportWarning(element: Element, message: CharSequence) {
        this.processingEnv.messager.printMessage(Diagnostic.Kind.WARNING, message, element)
    }

    private fun log(message: CharSequence) {
        this.processingEnv.messager.printMessage(Diagnostic.Kind.NOTE, message)
    }

    private fun cat(`in`: List<*>): CharSequence {
        val b = StringBuilder()
        for (i in `in`) {
            b.append("\"")
            b.append(i.toString())
            b.append("\", ")
        }
        return b
    }

    private fun logall(vararg rest: CharSequence) {
        for (m in rest) {
            log(m)
        }
    }
}
