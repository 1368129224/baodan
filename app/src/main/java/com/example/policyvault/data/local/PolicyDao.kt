package com.example.policyvault.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PolicyDao {
    @Query("SELECT * FROM policies ORDER BY createdAt DESC")
    fun observeAllPolicies(): Flow<List<PolicyEntity>>

    @Query("SELECT SUM(annualPremium) FROM policies")
    fun observeTotalAnnualPremium(): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPolicy(policy: PolicyEntity): Long

    @Delete
    suspend fun deletePolicy(policy: PolicyEntity)
}
