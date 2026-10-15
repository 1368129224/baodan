package com.example.policyvault.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.policyvault.BuildConfig
import com.example.policyvault.data.local.PolicyEntity
import com.example.policyvault.domain.updater.AppUpdater
import com.example.policyvault.domain.updater.UpdateInfo
import kotlinx.coroutines.launch
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    policies: List<PolicyEntity>,
    onImportPdfClick: () -> Unit,
    onDeletePolicy: (PolicyEntity) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val updater = remember { AppUpdater(context) }

    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }

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

    fun triggerCheckUpdate() {
        if (isCheckingUpdate || isDownloading) return
        isCheckingUpdate = true
        coroutineScope.launch {
            try {
                val info = updater.checkUpdate()
                updateInfo = info
                if (info.hasUpdate) {
                    showUpdateDialog = true
                } else {
                    Toast.makeText(context, "已是最新版本 (v1.0.0-${BuildConfig.GIT_COMMIT_HASH})", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "检查更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isCheckingUpdate = false
            }
        }
    }

    fun startDownloadAndInstall(info: UpdateInfo) {
        showUpdateDialog = false
        isDownloading = true
        downloadProgress = 0
        coroutineScope.launch {
            try {
                val apkFile = updater.downloadApk(info.downloadUrl) { progress ->
                    downloadProgress = progress
                }
                isDownloading = false
                Toast.makeText(context, "下载完成，正在调起安装...", Toast.LENGTH_SHORT).show()
                updater.installApk(apkFile)
            } catch (e: Exception) {
                isDownloading = false
                Toast.makeText(context, "更新下载失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
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
                        Text("保单管家", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    }
                },
                actions = {
                    Surface(
                        color = Color(0xFFDCFCE7),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(
                            text = "🔒 纯本地安全存储",
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
        containerColor = Color(0xFFF1F5F9)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
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

                // 2. 家庭成员横向标签栏
                if (familyMembers.size > 2) {
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
                    Spacer(modifier = Modifier.height(24.dp))
                    AppVersionFooter(
                        onCheckUpdateClick = { triggerCheckUpdate() },
                        isChecking = isCheckingUpdate
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        items(filteredPolicies, key = { it.id }) { policy ->
                            PolicyItemCard(
                                policy = policy,
                                onDelete = { onDeletePolicy(policy) }
                            )
                        }
                        item {
                            AppVersionFooter(
                                onCheckUpdateClick = { triggerCheckUpdate() },
                                isChecking = isCheckingUpdate
                            )
                        }
                    }
                }
            }

            // 底部操作区：左下角【检测更新】按钮 + 右下角【导入保单】按钮
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp)
            ) {
                // 左下角：检测新版本按钮
                FilledTonalButton(
                    onClick = { triggerCheckUpdate() },
                    modifier = Modifier.align(Alignment.BottomStart),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF0F172A)
                    ),
                    elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 2.dp)
                ) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF2563EB)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("检查中...", fontSize = 13.sp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = "检查更新",
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFF2563EB)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("检查更新", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }

                // 右下角：导入保单 PDF 浮动按钮
                ExtendedFloatingActionButton(
                    onClick = onImportPdfClick,
                    containerColor = Color(0xFF2563EB),
                    contentColor = Color.White,
                    icon = { Icon(Icons.Default.Add, contentDescription = "导入保单") },
                    text = { Text("导入保单 PDF", fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }

            // 下载进度弹窗
            if (isDownloading) {
                AlertDialog(
                    onDismissRequest = { /* 下载中不可关闭 */ },
                    confirmButton = {},
                    title = { Text("正在下载更新包") },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress / 100f },
                                modifier = Modifier.fillMaxWidth().height(8.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "正在从 GitHub 下载最新版本: $downloadProgress%",
                                fontSize = 13.sp,
                                color = Color(0xFF64748B)
                            )
                        }
                    }
                )
            }

            // 发现新版本更新弹窗
            if (showUpdateDialog && updateInfo != null) {
                val info = updateInfo!!
                val sizeText = if (info.apkSize > 0) " (${"%.1f".format(info.apkSize / (1024f * 1024f))} MB)" else ""
                AlertDialog(
                    onDismissRequest = { showUpdateDialog = false },
                    title = { Text("发现新版本") },
                    text = {
                        Column {
                            Text(
                                text = "版本: ${info.latestVersion}$sizeText",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2563EB)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = info.releaseNotes.ifBlank { "包含性能优化与修复。" },
                                fontSize = 13.sp,
                                color = Color(0xFF334155),
                                lineHeight = 18.sp
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { startDownloadAndInstall(info) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                        ) {
                            Text("立即下载并安装")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showUpdateDialog = false }) {
                            Text("稍后")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AppVersionFooter(
    onCheckUpdateClick: () -> Unit,
    isChecking: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "版本号：v1.0.0 (${BuildConfig.GIT_COMMIT_HASH})",
                fontSize = 12.sp,
                color = Color(0xFF64748B),
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "🔒 电子保单 100% 本地端侧解析 · 隐私无泄漏",
            fontSize = 11.sp,
            color = Color(0xFF94A3B8)
        )
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = if (selectedMember == "全部") "全家总保费支出预估" else "${selectedMember}的年缴保费",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "¥",
                    color = Color(0xFF38BDF8),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                val formattedMoney = DecimalFormat("#,##0.00").format(totalPremium)
                Text(
                    text = formattedMoney,
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "/ 年",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = Color(0xFF334155), thickness = 0.8.dp)
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "已纳入管理保单",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
                Text(
                    text = "$policyCount 份",
                    color = Color(0xFF38BDF8),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * 保单列表项卡片
 */
@Composable
private fun PolicyItemCard(
    policy: PolicyEntity,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 第一行：承保公司与被保人标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = policy.insurer.ifBlank { "保险公司" },
                    fontSize = 13.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Medium
                )
                Surface(
                    color = Color(0xFFEFF6FF),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "被保人: ${policy.insured.ifBlank { "--" }}",
                        color = Color(0xFF1D4ED8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 第二行：主险全称
            Text(
                text = policy.productName.ifBlank { "保险产品" },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A),
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 第三行：保单编号与保额/保费摘要
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "保单号: ${policy.policyNo.ifBlank { "--" }}",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "基本保额: ${formatSumInsured(policy.sumInsured)}",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "¥${DecimalFormat("#,##0.00").format(policy.annualPremium)}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEA580C)
                        )
                        Text(
                            text = "生效: ${policy.effectiveDate.ifBlank { "--" }}",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = onDelete,
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
            }
        }
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
