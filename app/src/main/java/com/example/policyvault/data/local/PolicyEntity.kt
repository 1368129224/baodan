package com.example.policyvault.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "policies")
data class PolicyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val insurer: String,           // 承保保司
    val policyNo: String,          // 保单合同号
    val applicant: String,         // 投保人姓名
    val insured: String,           // 被保险人姓名 (用于家庭成员分组)
    val productName: String,       // 主险产品名称
    val sumInsured: Double,        // 基本保额(元)
    val effectiveDate: String,     // 生效日期 YYYY-MM-DD
    val annualPremium: Double,     // 年缴保费(元)
    val pdfSandboxPath: String,    // 沙盒内原件 PDF 路径
    val coverImagePath: String,    // 首页预览图路径
    val createdAt: Long = System.currentTimeMillis()
)
