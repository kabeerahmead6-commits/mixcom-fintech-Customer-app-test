package com.mixcom.customer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

// === உங்கள் Backend URL மற்றும் Public Key-ஐ இங்கே மாற்றவும் ===
const val BACKEND_BASE_URL = "https://bwowvsrcehixusbkhctv.supabase.co"
const val BACKEND_PUBLIC_KEY = "sb_publishable_ohZfbMRrAR2GwR-GtytDhA_WZ0ZknTK"
const val DEFAULT_CUSTOMER_ID = "MXC-300001"
const val GOLD_RATE_PER_GRAM = 7850.0

val RoyalBlue = Color(0xFF1D3587)
val DeepBlue = Color(0xFF0E236B)
val BrandRed = Color(0xFFD32F2F)
val SoftBg = Color(0xFFF5F6FA)
val VipGold = Color(0xFFD4AF37)
val SuccessGreen = Color(0xFF2E7D32)

fun hashSecret(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

fun buildVirtualAccountNumber(phone: String, customerId: String): String {
    val digits = phone.filter { it.isDigit() }
    val tail = if (digits.length >= 6) digits.takeLast(6) else customerId.filter { it.isDigit() }.padStart(6, '0')
    return "MXVA01$tail"
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CustomerAppRoot()
            }
        }
    }
}

data class VirtualBankAccount(
    val customerId: String,
    val fullName: String,
    val phone: String,
    val accountNumber: String,
    val ifsc: String,
    val upiId: String,
    val providerTag: String = "EKO-CONNECTED",
    val status: String = "ACTIVE"
)

data class CustomerTxn(
    val refId: String,
    val category: String,
    val title: String,
    val amount: Double,
    val txnType: String,
    val status: String = "SUCCESS"
)

object CustomerBackend {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun restReq(path: String): Request.Builder {
        return Request.Builder()
            .url("$BACKEND_BASE_URL/rest/v1/$path")
            .addHeader("apikey", BACKEND_PUBLIC_KEY)
            .addHeader("Authorization", "Bearer $BACKEND_PUBLIC_KEY")
            .addHeader("Content-Type", "application/json")
    }

    suspend fun ensureVirtualAccount(
        customerId: String,
        fullName: String,
        phone: String
    ): VirtualBankAccount = withContext(Dispatchers.IO) {
        val vaNo = buildVirtualAccountNumber(phone, customerId)
        val ifsc = "ICIC0000104"
        val cleanHandle = customerId.lowercase().replace("-", "")
        val upi = "$cleanHandle@mixcompay"

        try {
            val payload = JSONObject()
                .put("customer_id", customerId)
                .put("full_name", fullName)
                .put("phone", phone)
                .put("virtual_account_no", vaNo)
                .put("ifsc_code", ifsc)
                .put("virtual_upi_id", upi)
                .put("provider_tag", "EKO-CONNECTED")
                .put("status", "ACTIVE")
                .toString()

            val req = restReq("customer_virtual_accounts?on_conflict=customer_id")
                .addHeader("Prefer", "resolution=ignore-duplicates,return=minimal")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().close()

            val getReq = restReq("customer_virtual_accounts?customer_id=eq.$customerId&select=*").get().build()
            client.newCall(getReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    if (arr.length() > 0) {
                        val o = arr.getJSONObject(0)
                        return@withContext VirtualBankAccount(
                            customerId = o.optString("customer_id", customerId),
                            fullName = o.optString("full_name", fullName),
                            phone = o.optString("phone", phone),
                            accountNumber = o.optString("virtual_account_no", vaNo),
                            ifsc = o.optString("ifsc_code", ifsc),
                            upiId = o.optString("virtual_upi_id", upi),
                            providerTag = o.optString("provider_tag", "EKO-CONNECTED"),
                            status = o.optString("status", "ACTIVE")
                        )
                    }
                }
            }
        } catch (_: Exception) { }

        VirtualBankAccount(customerId, fullName, phone, vaNo, ifsc, upi)
    }

    suspend fun fetchWalletAndGold(customerId: String): Pair<Double, Double> = withContext(Dispatchers.IO) {
        var bal = 0.0
        var grams = 0.0
        try {
            val wReq = restReq("wallets?user_id=eq.$customerId&select=balance").get().build()
            client.newCall(wReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    if (arr.length() > 0) bal = arr.getJSONObject(0).optDouble("balance", 0.0)
                }
            }
            val gReq = restReq("customer_gold_holdings?customer_id=eq.$customerId&select=gold_grams").get().build()
            client.newCall(gReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    if (arr.length() > 0) grams = arr.getJSONObject(0).optDouble("gold_grams", 0.0)
                }
            }
        } catch (_: Exception) { }
        Pair(bal, grams)
    }

    suspend fun syncWalletAndGold(
        customerId: String,
        newBalance: Double,
        newGoldGrams: Double,
        txn: CustomerTxn
    ) = withContext(Dispatchers.IO) {
        try {
            val wBody = JSONObject()
                .put("user_id", customerId)
                .put("balance", newBalance)
                .toString()
            val wReq = restReq("wallets?on_conflict=user_id")
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(wBody.toRequestBody(jsonMediaType))
                .build()
            client.newCall(wReq).execute().close()

            val gBody = JSONObject()
                .put("customer_id", customerId)
                .put("gold_grams", newGoldGrams)
                .toString()
            val gReq = restReq("customer_gold_holdings?on_conflict=customer_id")
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(gBody.toRequestBody(jsonMediaType))
                .build()
            client.newCall(gReq).execute().close()

            val tBody = JSONObject()
                .put("txn_ref", txn.refId)
                .put("customer_id", customerId)
                .put("category", txn.category)
                .put("title", txn.title)
                .put("amount", txn.amount)
                .put("txn_type", txn.txnType)
                .put("status", txn.status)
                .toString()
            val tReq = restReq("customer_txns")
                .addHeader("Prefer", "return=minimal")
                .post(tBody.toRequestBody(jsonMediaType))
                .build()
            client.newCall(tReq).execute().close()
        } catch (_: Exception) { }
    }

    suspend fun fetchTxns(customerId: String): List<CustomerTxn> = withContext(Dispatchers.IO) {
        try {
            val req = restReq("customer_txns?customer_id=eq.$customerId&order=created_at.desc&limit=30").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val arr = JSONArray(resp.body?.string().orEmpty())
                val list = mutableListOf<CustomerTxn>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        CustomerTxn(
                            refId = o.optString("txn_ref", "TXN-$i"),
                            category = o.optString("category", "BANK"),
                            title = o.optString("title", "Transaction"),
                            amount = o.optDouble("amount", 0.0),
                            txnType = o.optString("txn_type", "CREDIT"),
                            status = o.optString("status", "SUCCESS")
                        )
                    )
                }
                list
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

@Composable
fun CustomerAppRoot() {
    val context = LocalContext.current
    var isLoggedIn by remember { mutableStateOf(false) }
    var isUnlocked by remember { mutableStateOf(false) }

    var customerId by remember { mutableStateOf(DEFAULT_CUSTOMER_ID) }
    var fullName by remember { mutableStateOf("MiXCOM Customer") }
    var phone by remember { mutableStateOf("") }
    var mpinHash by remember { mutableStateOf<String?>(null) }

    if (!isLoggedIn) {
        CustomerLoginScreen(
            initialId = customerId,
            onProceed = { cid, name, mob ->
                customerId = cid
                fullName = name
                phone = mob
                isUnlocked = false
                isLoggedIn = true
            }
        )
        return
    }

    if (!isUnlocked) {
        CustomerMpinScreen(
            customerId = customerId,
            savedHash = mpinHash,
            onSetMpin = {
                mpinHash = it
                isUnlocked = true
                Toast.makeText(context, "MPIN set for $customerId", Toast.LENGTH_SHORT).show()
            },
            onUnlock = { isUnlocked = true }
        )
        return
    }

    CustomerMainShell(
        customerId = customerId,
        fullName = fullName,
        phone = phone,
        onLock = { isUnlocked = false },
        onLogout = {
            isUnlocked = false
            isLoggedIn = false
        }
    )
}

@Composable
fun CustomerLoginScreen(
    initialId: String,
    onProceed: (String, String, String) -> Unit
) {
    val context = LocalContext.current
    var cid by remember { mutableStateOf(initialId) }
    var name by remember { mutableStateOf("") }
    var mob by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DeepBlue, RoyalBlue)))
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(color = VipGold, shape = RoundedCornerShape(6.dp)) {
            Text(
                "MIXCOM CONNECTED BANKING",
                color = Color.Black,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("MiXCOM Pay", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "Virtual Bank Account, 24K Gold & Instant Payments",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = cid,
                    onValueChange = { cid = it.uppercase().trim() },
                    label = { Text("Customer ID (e.g. MXC-300001)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Your Full Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mob,
                    onValueChange = { if (it.length <= 10 && it.all { c -> c.isDigit() }) mob = it },
                    label = { Text("Mobile Number (10 digits)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (mob.length != 10) {
                            Toast.makeText(context, "Enter 10-digit mobile number", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val cleanId = cid.trim().ifBlank { DEFAULT_CUSTOMER_ID }
                        val cleanName = name.trim().ifBlank { "Customer ($cleanId)" }
                        onProceed(cleanId, cleanName, mob.trim())
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("Activate Virtual Account", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
fun CustomerMpinScreen(
    customerId: String,
    savedHash: String?,
    onSetMpin: (String) -> Unit,
    onUnlock: () -> Unit
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val isSetup = savedHash.isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DeepBlue, RoyalBlue)))
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(color = VipGold, shape = RoundedCornerShape(6.dp)) {
            Text(
                "ID: $customerId",
                color = Color.Black,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (isSetup) "Set 6-Digit MPIN" else "Enter 6-Digit MPIN",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = RoyalBlue
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pin = it },
                    label = { Text("6-Digit MPIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isSetup) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) confirm = it },
                        label = { Text("Confirm MPIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Button(
                    onClick = {
                        if (pin.length != 6) {
                            Toast.makeText(context, "Enter 6-digit MPIN", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (isSetup) {
                            if (pin != confirm) {
                                Toast.makeText(context, "MPIN mismatch", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            onSetMpin(hashSecret(pin))
                        } else {
                            if (hashSecret(pin) == savedHash) onUnlock()
                            else Toast.makeText(context, "Invalid MPIN", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(if (isSetup) "Save MPIN" else "Unlock", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun CustomerMainShell(
    customerId: String,
    fullName: String,
    phone: String,
    onLock: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var walletBalance by remember { mutableDoubleStateOf(0.0) }
    var goldGrams by remember { mutableDoubleStateOf(0.0) }
    var virtualAccount by remember {
        mutableStateOf(
            VirtualBankAccount(
                customerId = customerId,
                fullName = fullName,
                phone = phone,
                accountNumber = buildVirtualAccountNumber(phone, customerId),
                ifsc = "ICIC0000104",
                upiId = "${customerId.lowercase().replace("-", "")}@mixcompay"
            )
        )
    }
    val txns = remember { mutableStateListOf<CustomerTxn>() }

    fun refreshAll(showMsg: Boolean = false) {
        scope.launch {
            val va = CustomerBackend.ensureVirtualAccount(customerId, fullName, phone)
            val (bal, grams) = CustomerBackend.fetchWalletAndGold(customerId)
            val list = CustomerBackend.fetchTxns(customerId)
            virtualAccount = va
            walletBalance = bal
            goldGrams = grams
            txns.clear()
            txns.addAll(list)
            if (showMsg) Toast.makeText(context, "Synced with cloud", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(customerId) {
        refreshAll(showMsg = false)
    }

    fun applyTransaction(
        category: String,
        title: String,
        amount: Double,
        txnType: String,
        deltaBalance: Double,
        deltaGoldGrams: Double = 0.0
    ): Boolean {
        val nextBal = walletBalance + deltaBalance
        val nextGold = goldGrams + deltaGoldGrams
        if (nextBal < 0.0 || nextGold < 0.0) {
            Toast.makeText(context, "Insufficient balance", Toast.LENGTH_SHORT).show()
            return false
        }
        walletBalance = nextBal
        goldGrams = nextGold
        val newTxn = CustomerTxn(
            refId = "MXT-${(100000..999999).random()}",
            category = category,
            title = title,
            amount = amount,
            txnType = txnType
        )
        txns.add(0, newTxn)
        scope.launch {
            CustomerBackend.syncWalletAndGold(customerId, nextBal, nextGold, newTxn)
        }
        return true
    }

    Scaffold(
        containerColor = SoftBg,
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Outlined.AccountBalance, contentDescription = null) },
                    label = { Text("Banking", fontSize = 11.sp) }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Outlined.Savings, contentDescription = null) },
                    label = { Text("24K Gold", fontSize = 11.sp) }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Outlined.ReceiptLong, contentDescription = null) },
                    label = { Text("History", fontSize = 11.sp) }
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Outlined.AccountCircle, contentDescription = null) },
                    label = { Text("Profile", fontSize = 11.sp) }
                )
            }
        }
    ) { pad ->
        Box(modifier = Modifier.padding(pad)) {
            when (tab) {
                0 -> ConnectedBankingTab(
                    walletBalance = walletBalance,
                    virtualAccount = virtualAccount,
                    onRefresh = { refreshAll(showMsg = true) },
                    onAddMoney = { amt ->
                        if (applyTransaction("VIRTUAL_VA", "Virtual Account Top-up", amt, "CREDIT", +amt)) {
                            Toast.makeText(context, "₹$amt credited to account", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onPayService = { svcLabel, amt ->
                        if (applyTransaction("CONNECTED_PAY", svcLabel, amt, "DEBIT", -amt)) {
                            Toast.makeText(context, "$svcLabel completed", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                1 -> CustomerGoldTab(
                    walletBalance = walletBalance,
                    goldGrams = goldGrams,
                    onBuyGold = { rupees ->
                        val grams = rupees / GOLD_RATE_PER_GRAM
                        if (applyTransaction("GOLD_BUY", "Buy 24K Gold (${"%.4f".format(grams)}g)", rupees, "DEBIT", -rupees, +grams)) {
                            Toast.makeText(context, "Bought ${"%.4f".format(grams)}g 24K Gold", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSellGold = { grams ->
                        val rupees = grams * GOLD_RATE_PER_GRAM
                        if (applyTransaction("GOLD_SELL", "Sell 24K Gold (${"%.4f".format(grams)}g)", rupees, "CREDIT", +rupees, -grams)) {
                            Toast.makeText(context, "Sold ${"%.4f".format(grams)}g gold for ₹${"%.2f".format(rupees)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                2 -> CustomerHistoryTab(txns = txns)
                3 -> CustomerProfileTab(
                    virtualAccount = virtualAccount,
                    onLock = onLock,
                    onLogout = onLogout
                )
            }
        }
    }
}

@Composable
fun ConnectedBankingTab(
    walletBalance: Double,
    virtualAccount: VirtualBankAccount,
    onRefresh: () -> Unit,
    onAddMoney: (Double) -> Unit,
    onPayService: (String, Double) -> Unit
) {
    val context = LocalContext.current
    var topupAmt by remember { mutableStateOf("500") }
    var serviceKind by remember { mutableStateOf("Money Transfer") }
    var targetInfo by remember { mutableStateOf("") }
    var payAmt by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(DeepBlue, RoyalBlue)))
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(virtualAccount.customerId, color = VipGold, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        Text(virtualAccount.fullName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier
                            .size(40.dp)
                            .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("AVAILABLE BALANCE", color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
                Text("₹ ${"%.2f".format(walletBalance)}", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("My Virtual Bank Account", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = RoyalBlue)
                        Surface(color = Color(0xFFE8F5E9), shape = RoundedCornerShape(6.dp)) {
                            Text(
                                virtualAccount.status,
                                color = SuccessGreen,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Text("Account No: ${virtualAccount.accountNumber}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("IFSC Code: ${virtualAccount.ifsc}", fontSize = 13.sp, color = Color.DarkGray)
                    Text("Virtual UPI ID: ${virtualAccount.upiId}", fontSize = 13.sp, color = Color.DarkGray)
                    Text("Engine: ${virtualAccount.providerTag}", fontSize = 11.sp, color = Color.Gray)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { copyToClipboard(context, "Virtual Account No", virtualAccount.accountNumber) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Copy A/C", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { copyToClipboard(context, "Virtual UPI ID", virtualAccount.upiId) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Copy UPI", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Add Money to Virtual Account", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = RoyalBlue)
                    OutlinedTextField(
                        value = topupAmt,
                        onValueChange = { topupAmt = it },
                        label = { Text("Amount (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val v = topupAmt.toDoubleOrNull() ?: 0.0
                            if (v <= 0.0) {
                                Toast.makeText(context, "Enter valid amount", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            onAddMoney(v)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text("Credit to Wallet", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Connected Payments (Transfer / Recharge / BBPS)", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = RoyalBlue)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Money Transfer", "Recharge", "BBPS").forEach { kind ->
                            FilterChip(
                                selected = serviceKind == kind,
                                onClick = { serviceKind = kind },
                                label = { Text(kind, fontSize = 11.sp) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = targetInfo,
                        onValueChange = { targetInfo = it },
                        label = { Text("Beneficiary A/C, Mobile, or Biller No") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = payAmt,
                        onValueChange = { payAmt = it },
                        label = { Text("Amount (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val v = payAmt.toDoubleOrNull() ?: 0.0
                            if (targetInfo.isBlank() || v <= 0.0) {
                                Toast.makeText(context, "Enter details and amount", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            onPayService("$serviceKind (${targetInfo.trim()})", v)
                            targetInfo = ""
                            payAmt = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text("Pay Now", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerGoldTab(
    walletBalance: Double,
    goldGrams: Double,
    onBuyGold: (Double) -> Unit,
    onSellGold: (Double) -> Unit
) {
    val context = LocalContext.current
    var rupeeInput by remember { mutableStateOf("200") }
    var gramSellInput by remember { mutableStateOf("0.01") }

    Column(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = RoyalBlue),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("24K DIGITAL GOLD", color = VipGold, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                Text("${"%.4f".format(goldGrams)} g", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
                Text("Rate: ₹${"%.0f".format(GOLD_RATE_PER_GRAM)}/g • Wallet: ₹${"%.2f".format(walletBalance)}", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Buy 24K Gold", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = RoyalBlue)
                OutlinedTextField(
                    value = rupeeInput,
                    onValueChange = { rupeeInput = it },
                    label = { Text("Amount in ₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val amt = rupeeInput.toDoubleOrNull() ?: 0.0
                        if (amt <= 0.0) {
                            Toast.makeText(context, "Enter valid ₹ amount", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        onBuyGold(amt)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Text("Buy Gold", fontWeight = FontWeight.Bold)
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sell 24K Gold", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = RoyalBlue)
                OutlinedTextField(
                    value = gramSellInput,
                    onValueChange = { gramSellInput = it },
                    label = { Text("Grams to Sell (e.g. 0.01)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = {
                        val g = gramSellInput.toDoubleOrNull() ?: 0.0
                        if (g <= 0.0) {
                            Toast.makeText(context, "Enter valid grams", Toast.LENGTH_SHORT).show()
                            return@OutlinedButton
                        }
                        onSellGold(g)
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Text("Sell Gold to Wallet", fontWeight = FontWeight.Bold, color = RoyalBlue)
                }
            }
        }
    }
}

@Composable
fun CustomerHistoryTab(txns: List<CustomerTxn>) {
    if (txns.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No transactions yet.", color = Color.Gray)
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(txns) { t ->
            val isCredit = t.txnType.equals("CREDIT", ignoreCase = true)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(t.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${t.category} • ${t.refId}", color = Color.Gray, fontSize = 11.sp)
                    }
                    Text(
                        "${if (isCredit) "+" else "-"} ₹${"%.2f".format(t.amount)}",
                        color = if (isCredit) SuccessGreen else BrandRed,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun CustomerProfileTab(
    virtualAccount: VirtualBankAccount,
    onLock: () -> Unit,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(color = RoyalBlue, shape = RoundedCornerShape(10.dp)) {
            Text(
                "MiXCOM Pay Customer",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
            )
        }
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Customer ID: ${virtualAccount.customerId}", fontWeight = FontWeight.ExtraBold, color = BrandRed)
                Text("Name: ${virtualAccount.fullName}", fontSize = 14.sp)
                Text("Phone: ${virtualAccount.phone}", fontSize = 13.sp, color = Color.Gray)
                Text("Virtual A/C: ${virtualAccount.accountNumber}", fontSize = 13.sp, color = Color.Gray)
                Text("Virtual UPI: ${virtualAccount.upiId}", fontSize = 13.sp, color = Color.Gray)
            }
        }
        Button(
            onClick = onLock,
            colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Lock App")
        }
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = BrandRed)
            Spacer(Modifier.width(8.dp))
            Text("Sign Out", color = BrandRed, fontWeight = FontWeight.Bold)
        }
    }
}
