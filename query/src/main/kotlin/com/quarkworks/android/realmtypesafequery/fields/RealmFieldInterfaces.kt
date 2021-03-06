package com.quarkworks.android.realmtypesafequery.fields

import io.realm.RealmModel
import io.realm.RealmQuery
import io.realm.Sort

interface RealmField<Model : RealmModel> {
    val name: String
    val modelClass: Class<Model>

    val isIndexed: Boolean
        get() = this is RealmIndexedField<*>
    val isSortable: Boolean
        get() = this is RealmSortableField<*>
}

interface RealmNullableField<Model : RealmModel> : RealmField<Model> {
    fun never(query: RealmQuery<Model>)

    fun isNull(query: RealmQuery<Model>) {
        try {
            query.isNull(name) // throws IllegalArgumentException if field is Required
        } catch (ignored: IllegalArgumentException) {
            never(query)
        }
    }

    fun isNotNull(query: RealmQuery<Model>) {
        try {
            query.isNotNull(name) // throws IllegalArgumentException if field is Required
        } catch (ignored: IllegalArgumentException) {
            // do nothing
        }
    }
}

interface RealmEquatableField<Model : RealmModel, in Value> : RealmNullableField<Model> {
    fun equalTo(query: RealmQuery<Model>, value: Value?)
    fun notEqualTo(query: RealmQuery<Model>, value: Value?)
}

interface RealmComparableField<Model : RealmModel, in Value> : RealmEquatableField<Model, Value> {
    fun greaterThan(query: RealmQuery<Model>, value: Value?)
    fun greaterThanOrEqualTo(query: RealmQuery<Model>, value: Value?)
    fun lessThan(query: RealmQuery<Model>, value: Value?)
    fun lessThanOrEqualTo(query: RealmQuery<Model>, value: Value?)
    fun between(query: RealmQuery<Model>, start: Value?, end: Value?)
}

interface RealmEmptyableField<Model : RealmModel> : RealmField<Model> {
    fun isEmpty(query: RealmQuery<Model>) {
        query.isEmpty(name)
    }

    fun isNotEmpty(query: RealmQuery<Model>) {
        query.isNotEmpty(name)
    }
}

interface RealmIndexedField<Model : RealmModel> : RealmField<Model>
interface RealmSortableField<Model : RealmModel> : RealmField<Model> {
    fun sort(query: RealmQuery<Model>) : RealmQuery<Model> = query.sort(name)
    fun sort(query: RealmQuery<Model>, sortOrder: Sort) : RealmQuery<Model> = query.sort(name, sortOrder)
    fun sort(query: RealmQuery<Model>, sortOrder: Sort, otherField: RealmSortableField<Model>, otherSortOrder: Sort) : RealmQuery<Model> =
            query.sort(name, sortOrder, otherField.name, otherSortOrder)
    companion object {
        fun <Model : RealmModel> sort(query: RealmQuery<Model>, fields: Array<RealmSortableField<Model>>, sortOrders: Array<Sort>) : RealmQuery<Model> =
                query.sort(fields.map { it.name }.toTypedArray(), sortOrders)
    }

}

interface RealmInableField<Model : RealmModel, Value> : RealmField<Model> {
    fun `in`(query: RealmQuery<Model>, values: Array<Value>)
    fun `in`(query: RealmQuery<Model>, values: List<Value>)
}

interface RealmDistinctableField<Model : RealmModel> : RealmField<Model> {
    fun distinct(query: RealmQuery<Model>) : RealmQuery<Model> = query.distinct(name)
    fun distinct(query: RealmQuery<Model>, vararg otherFields: RealmDistinctableField<Model>) : RealmQuery<Model> =
            query.distinct(name, *otherFields.map { it.name }.toTypedArray())
}

interface RealmMinMaxField<Model : RealmModel, out Value> : RealmField<Model> {
    fun min(query: RealmQuery<Model>) : Value?
    fun max(query: RealmQuery<Model>) : Value?
}

interface RealmAggregatableField<Model : RealmModel, out Value> : RealmField<Model> {
    fun sum(query: RealmQuery<Model>) : Value
    fun average(query: RealmQuery<Model>) : Double = query.average(name)
}