package com.example.policyvault.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.policyvault.data.local.PolicyEntity
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    policies: List<PolicyEntity>,
    onImportPdfClick: () -> Unit,
    onDeletePolicy: (PolicyEntity) -> Unit
) {
    // 提取所有不重复的“被保险人”姓名，生成家庭成员筛选标签
    val familyMembers = remember(policies) {
        listOf("全部") + policies.map { it.insured.ifBlank { "未命名" } }.distinct()
    }
    var selectedMember by remember { mutableStateOf("全部") }

    // 根据当前选中的家庭成员过滤保单列表
    val filteredPolicies = remember(policies, selectedMember) {
        if (selectedMember == "全部") policies
        else policies.filter { (it.insured.ifBlank { "未命名" }) == selectedMember }
    }

    // 计算当前视图下的年缴总保费
    val totalAnnualPremium = remember(filteredPolicies) {
        filteredPolicies.sumOf { it.annualPremium }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = Color(0xFF2563EB),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("离线保单管家", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    }
                },
                actions = {
                    Surface(
                        color = Color(0xFFDCFCE7),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(
                            text = "🔒 纯本地零联网",
                            color = Color(0xFF15803D),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onImportPdfClick,
                containerColor = Color(0xFF2563EB),
                contentColor = Color.White,
                icon = { Icon(Icons.Default.Add, contentDescription = "导入保单") },
                text = { Text("导入保单 PDF", fontWeight = FontWeight.SemiBold) }
            )
        },
        containerColor = Color(0xFFF1F5F9)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // 1. 顶部家庭保单统计总览卡片
            SummaryDashboardCard(
                policyCount = filteredPolicies.size,
                totalPremium = totalAnnualPremium,
                selectedMember = selectedMember
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. 家庭成员（被保险人）横向筛选栏
            if (policies.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    familyMembers.forEach { member ->
                        FilterChip(
                            selected = selectedMember == member,
                            onClick = { selectedMember = member },
                            label = { Text(member, fontSize = 13.sp) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 3. 保单列表或空状态引导
            if (filteredPolicies.isEmpty()) {
                EmptyPolicyGuide(onImportPdfClick = onImportPdfClick)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(filteredPolicies, key = { it.id }) { policy ->
                        PolicyItemCard(
                            policy = policy,
                            onDelete = { onDeletePolicy(policy) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 顶部统计看板：展示有效保单数量与年缴总保费
 */
@Composable
private fun SummaryDashboardCard(
    policyCount: Int,
    totalPremium: Double,
    selectedMember: String
) {
    val decimalFormat = remember { DecimalFormat("#,##0.00") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (selectedMember == "全部") "家庭有效保单" else "${selectedMember} 的保单",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$policyCount",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "份",
                        color = Color(0xFFCBD5E1),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "年缴总保费预估",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "¥ ${decimalFormat.format(totalPremium)}",
                    color = Color(0xFF38BDF8),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 单张已导入保单展示卡片
 */
@Composable
private fun PolicyItemCard(
    policy: PolicyEntity,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 第一行：承保保司标签 + 保单号 + 删除按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = Color(0xFFEFF6FF),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = policy.insurer.ifBlank { "未知保司" },
                            color = Color(0xFF1D4ED8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "单号: ${policy.policyNo.ifBlank { "--" }}",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp
                    )
                }

                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "删除保单",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 第二行：主险产品名称
            Text(
                text = policy.productName.ifBlank { "未命名保险产品" },
                color = Color(0xFF0F172A),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 第三行：三列核心指标（被保险人 | 基本保额 | 年缴保费）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn(label = "被保险人", value = policy.insured.ifBlank { "--" })
                MetricColumn(label = "基本保额", value = formatSumInsured(policy.sumInsured))
                MetricColumn(label = "年缴保费", value = "¥${policy.annualPremium.toInt()}/年")
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 第四行：投保人与生效日期
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "投保人：${policy.applicant.ifBlank { "--" }}",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp
                )
                Text(
                    text = "生效日：${policy.effectiveDate.ifBlank { "--" }}",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认移除该保单？") },
            text = { Text("移除后将删除本地保单记录「${policy.productName}」。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }
                ) {
                    Text("确认删除", color = Color(0xFFEF4444))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun MetricColumn(label: String, value: String) {
    Column {
        Text(text = label, color = Color(0xFF64748B), fontSize = 11.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, color = Color(0xFF0F172A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 空状态引导卡片：指导用户点击按钮选择本地 PDF 或从微信直接分享导入
 */
@Composable
private fun EmptyPolicyGuide(onImportPdfClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = Color(0xFF3B82F6),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "尚未导入任何保单",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "支持两种方式离线导入电子保单 PDF：\n1. 点击下方按钮，从手机本地文件中选择 PDF\n2. 在微信打开保单 PDF，点击右上角「用其他应用打开」选择本应用",
                fontSize = 13.sp,
                color = Color(0xFF64748B),
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onImportPdfClick,
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("立即选择保单 PDF 导入")
            }
        }
    }
}

private fun formatSumInsured(amount: Double): String {
    return when {
        amount >= 10000.0 -> {
            val wan = amount / 10000.0
            if (wan % 1.0 == 0.0) "${wan.toInt()}万元" else "${wan}万元"
        }
        amount > 0 -> "${amount.toInt()}元"
        else -> "--"
    }
}
