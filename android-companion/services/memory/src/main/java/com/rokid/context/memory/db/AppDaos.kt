package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface EndpointDao {
    @Upsert suspend fun upsert(endpoint: EndpointEntity)
    @Query("SELECT * FROM endpoint WHERE id = :id") suspend fun byId(id: String): EndpointEntity?
    @Query("SELECT * FROM endpoint ORDER BY createdAt ASC") suspend fun all(): List<EndpointEntity>
    @Query("DELETE FROM endpoint WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface RoleBindingDao {
    @Upsert suspend fun upsert(binding: RoleBindingEntity)
    @Query("SELECT * FROM role_binding WHERE role = :role") suspend fun byRole(role: String): RoleBindingEntity?
    @Query("SELECT * FROM role_binding") suspend fun all(): List<RoleBindingEntity>
    @Query("DELETE FROM role_binding WHERE role = :role") suspend fun delete(role: String)
}
