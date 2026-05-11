package com.videoplayer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.PipeShare
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BrowserActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnAddServer: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var tvEmptyIcon: TextView
    private lateinit var tvEmptyText: TextView

    private val executor = Executors.newCachedThreadPool()
    private val handler = Handler(Looper.getMainLooper())
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private var smbClient: SMBClient? = null
    private var smbSession: Session? = null
    private var diskShare: DiskShare? = null
    private var currentServer = ""
    private var currentShareName = ""
    private var currentUser = ""
    private var currentPass = ""
    private var currentPath = ""
    private var browseMode = BrowseMode.ROOT
    private val foundIps = mutableSetOf<String>()

    private enum class BrowseMode { ROOT, LOCAL_FILES, NETWORK_SERVERS, SMB_SHARES, SMB_FILES }

    private val items = mutableListOf<Item>()
    private lateinit var adapter: ItemAdapter

    // Favorites: stored as "server|share|path|user|pass" strings
    private fun getFavorites(): MutableSet<String> {
        val prefs = getSharedPreferences("browser_prefs", MODE_PRIVATE)
        return prefs.getStringSet("favorites", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveFavorites(favs: Set<String>) {
        getSharedPreferences("browser_prefs", MODE_PRIVATE).edit()
            .putStringSet("favorites", favs).apply()
    }

    private fun toggleFavorite(server: String, share: String, path: String, user: String, pass: String) {
        val key = "$server|$share|$path|$user|$pass"
        val favs = getFavorites()
        if (key in favs) favs.remove(key) else favs.add(key)
        saveFavorites(favs)
        val label = if (path.isNotEmpty()) path.substringAfterLast("\\") else share
        Toast.makeText(this, if (key in getFavorites()) "★ $label added to favorites" else "$label removed from favorites", Toast.LENGTH_SHORT).show()
    }

    private fun isFavorite(server: String, share: String, path: String): Boolean {
        val key = "$server|$share|$path|$currentUser|$currentPass"
        return key in getFavorites()
    }

    // Last path persistence
    private fun saveLastPath() {
        if (browseMode == BrowseMode.SMB_FILES) {
            getSharedPreferences("browser_prefs", MODE_PRIVATE).edit()
                .putString("last_server", currentServer)
                .putString("last_share", currentShareName)
                .putString("last_path", currentPath)
                .putString("last_user", currentUser)
                .putString("last_pass", currentPass)
                .apply()
        }
    }

    data class Item(val name: String, val subtitle: String = "", val type: ItemType, val size: Long = 0)
    enum class ItemType { DEVICE, SERVER, SHARE, FOLDER, VIDEO }

    private val storagePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) browseLocalFiles(Environment.getExternalStorageDirectory().absolutePath) }

    companion object {
        const val EXTRA_FILE_MODE = "file_mode" // "video" (default) or "subtitle"
        const val EXTRA_START_SERVER = "start_server"
        const val EXTRA_START_SHARE = "start_share"
        const val EXTRA_START_PATH = "start_path"
        const val EXTRA_START_USER = "start_user"
        const val EXTRA_START_PASS = "start_pass"
        const val RESULT_MODE = "result_mode"
        const val RESULT_LOCAL_URI = "local_uri"
        const val RESULT_SMB_SERVER = "smb_server"
        const val RESULT_SMB_SHARE = "smb_share"
        const val RESULT_SMB_PATH = "smb_path"
        const val RESULT_SMB_USER = "smb_user"
        const val RESULT_SMB_PASS = "smb_pass"
        const val RESULT_SMB_DOMAIN = "smb_domain"
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v",
            "mpg", "mpeg", "3gp", "ts", "mts", "m2ts", "vob", "ogv"
        )
        private val SUBTITLE_EXTENSIONS = setOf(
            "srt", "ass", "ssa", "sub", "vtt", "idx", "sup", "lrc"
        )
    }

    private lateinit var fileExtensions: Set<String>
    private var fileMode = "video"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smb_browser)

        fileMode = intent.getStringExtra(EXTRA_FILE_MODE) ?: "video"
        fileExtensions = if (fileMode == "subtitle") SUBTITLE_EXTENSIONS else VIDEO_EXTENSIONS

        // Load background image
        try {
            val bgStream = assets.open("browser_bg.png")
            val bgBitmap = android.graphics.BitmapFactory.decodeStream(bgStream)
            bgStream.close()
            findViewById<android.widget.ImageView>(R.id.bgImage).setImageBitmap(bgBitmap)
        } catch (_: Exception) {}

        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        btnBack = findViewById(R.id.btnBack)
        btnAddServer = findViewById(R.id.btnAddServer)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        recyclerView = findViewById(R.id.recyclerView)
        emptyState = findViewById(R.id.emptyState)
        tvEmptyIcon = findViewById(R.id.tvEmptyIcon)
        tvEmptyText = findViewById(R.id.tvEmptyText)
        adapter = ItemAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        swipeRefresh.setColorSchemeColors(0xFF6750A4.toInt())
        swipeRefresh.setOnRefreshListener { refresh() }
        btnBack.setOnClickListener { goBack() }
        btnAddServer.setOnClickListener { showAddDialog() }
        findViewById<android.widget.ImageButton>(R.id.btnBrowserSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // Check if we should start in a specific folder (e.g., subtitle loading from same dir)
        val startServer = intent.getStringExtra(EXTRA_START_SERVER)
        val startShare = intent.getStringExtra(EXTRA_START_SHARE)
        val startPath = intent.getStringExtra(EXTRA_START_PATH)
        if (startServer != null && startShare != null) {
            currentUser = intent.getStringExtra(EXTRA_START_USER) ?: ""
            currentPass = intent.getStringExtra(EXTRA_START_PASS) ?: ""
            executor.execute {
                try {
                    connectSmbDirect(startServer, startShare, currentUser, currentPass)
                    runOnUiThread { listSmbDirAndShow(startPath ?: "") }
                } catch (e: Exception) {
                    runOnUiThread { showRoot() }
                }
            }
        } else {
            showRoot()
        }
    }

    private fun connectSmbDirect(server: String, share: String, user: String, pass: String) {
        disconnectSmb()
        currentServer = server
        currentShareName = share
        val config = SmbConfig.builder().withTimeout(15, TimeUnit.SECONDS).build()
        smbClient = SMBClient(config)
        val conn = smbClient!!.connect(server)
        val auth = if (user.isNotEmpty()) AuthenticationContext(user, pass.toCharArray(), "")
        else AuthenticationContext.guest()
        smbSession = conn.authenticate(auth)
        diskShare = smbSession!!.connectShare(share) as DiskShare
    }

    private fun listSmbDirAndShow(path: String) {
        currentPath = path
        browseMode = BrowseMode.SMB_FILES
        progressBar.visibility = View.VISIBLE
        executor.execute { listSmbDir(path) }
    }

    // ── Root ─────────────────────────────────────────────────────────

    private fun showRoot() {
        browseMode = BrowseMode.ROOT
        items.clear()
        btnBack.visibility = View.GONE
        btnAddServer.visibility = View.GONE
        val isSubMode = fileExtensions == SUBTITLE_EXTENSIONS
        tvTitle.text = if (isSubMode) "Load Subtitle" else "Browse"
        tvSubtitle.visibility = View.GONE
        progressBar.visibility = View.GONE
        emptyState.visibility = View.GONE
        // Last path shortcut
        val prefs = getSharedPreferences("browser_prefs", MODE_PRIVATE)
        val lastServer = prefs.getString("last_server", null)
        val lastShare = prefs.getString("last_share", null)
        val lastPath = prefs.getString("last_path", null)
        if (lastServer != null && lastShare != null) {
            val label = if (lastPath.isNullOrEmpty()) lastShare else lastPath.substringAfterLast("\\")
            items.add(Item("↩ $label", "\\\\$lastServer\\$lastShare", ItemType.SHARE))
        }

        // Favorites
        for (fav in getFavorites()) {
            val parts = fav.split("|", limit = 5)
            if (parts.size >= 3) {
                val label = if (parts[2].isNotEmpty()) parts[2].substringAfterLast("\\") else parts[1]
                items.add(Item("★ $label", "\\\\${parts[0]}\\${parts[1]}", ItemType.SHARE))
            }
        }

        items.add(Item("This Device", if (isSubMode) "Browse local subtitle files" else "Browse local video files", ItemType.DEVICE))
        items.add(Item("Local Network", "Browse SMB/Samba shares", ItemType.SERVER))
        adapter.notifyDataSetChanged()
        // Pre-discover network servers in background
        foundIps.clear()
        executor.execute { discoverNetBIOS() }
        executor.execute { discoverPortScan() }
        startNsdDiscovery()
    }

    // ── Local files ──────────────────────────────────────────────────

    private fun openLocalFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+: need MANAGE_EXTERNAL_STORAGE for full file browsing
            if (Environment.isExternalStorageManager()) {
                browseLocalFiles(Environment.getExternalStorageDirectory().absolutePath)
            } else {
                try {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    ))
                } catch (_: Exception) {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            browseLocalFiles(Environment.getExternalStorageDirectory().absolutePath)
        } else {
            storagePermLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun browseLocalFiles(path: String) {
        browseMode = BrowseMode.LOCAL_FILES
        currentPath = path
        btnBack.visibility = View.VISIBLE
        btnAddServer.visibility = View.GONE
        progressBar.visibility = View.GONE
        emptyState.visibility = View.GONE
        val dir = File(path)
        tvTitle.text = if (path == Environment.getExternalStorageDirectory().absolutePath) "This Device" else dir.name
        tvSubtitle.text = path
        tvSubtitle.visibility = View.VISIBLE
        val dirs = mutableListOf<Item>()
        val videos = mutableListOf<Item>()
        dir.listFiles()?.forEach { file ->
            if (file.name.startsWith(".")) return@forEach
            if (file.isDirectory) dirs.add(Item(file.name, "", ItemType.FOLDER))
            else {
                val ext = file.extension.lowercase()
                if (ext in fileExtensions) videos.add(Item(file.name, formatSize(file.length()), ItemType.VIDEO, file.length()))
            }
        }
        dirs.sortBy { it.name.lowercase() }
        videos.sortBy { it.name.lowercase() }
        items.clear(); items.addAll(dirs); items.addAll(videos)
        adapter.notifyDataSetChanged()
        swipeRefresh.isRefreshing = false
        if (items.isEmpty()) showEmpty("No videos or folders found")

    }

    private fun selectLocalFile(fileName: String) {

        val uri = Uri.fromFile(File(currentPath, fileName))
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra(RESULT_MODE, "local")
            putExtra(RESULT_LOCAL_URI, uri.toString())
        })
        finish()
    }

    // ── Network servers ──────────────────────────────────────────────

    private fun showNetworkServers() {
        browseMode = BrowseMode.NETWORK_SERVERS
        items.clear()
        adapter.notifyDataSetChanged()
        btnBack.visibility = View.VISIBLE
        btnAddServer.visibility = View.VISIBLE
        tvTitle.text = "Local Network"
        tvSubtitle.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        showEmpty("Searching for servers...")
        synchronized(foundIps) {
            for (ip in foundIps) {
                val dn = try { val r = InetAddress.getByName(ip).canonicalHostName; if (r != ip) r else ip } catch (_: Exception) { ip }
                items.add(Item(dn, if (dn != ip) ip else "SMB Server", ItemType.SERVER))
            }
        }
        if (items.isNotEmpty()) { items.sortBy { it.name.lowercase() }; adapter.notifyDataSetChanged(); emptyState.visibility = View.GONE }
        executor.execute { discoverNetBIOS() }
        executor.execute { discoverPortScan() }
        startNsdDiscovery()
        handler.postDelayed({
            progressBar.visibility = View.GONE; swipeRefresh.isRefreshing = false
            if (items.isEmpty()) showEmpty("No servers found\nPull down to retry or tap + to add manually")
        }, 8000)

    }

    private fun discoverNetBIOS() {
        try {
            val ba = getBroadcastAddress() ?: return
            val q = buildNetBIOSQuery(); val s = DatagramSocket(); s.soTimeout = 4000; s.broadcast = true
            s.send(DatagramPacket(q, q.size, ba, 137))
            val buf = ByteArray(1024); val end = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < end) {
                try { val r = DatagramPacket(buf, buf.size); s.receive(r); addServer(r.address.hostAddress ?: continue, parseNetBIOSResponse(buf, r.length)) }
                catch (_: java.net.SocketTimeoutException) { break } catch (_: Exception) {}
            }
            s.close()
        } catch (_: Exception) {}
    }

    private fun discoverPortScan() {
        val lip = getLocalIp() ?: return; val pfx = lip.substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(50)
        val futures = (1..254).map { i -> pool.submit { try { Socket().use { it.connect(InetSocketAddress("$pfx.$i", 445), 600) }; addServer("$pfx.$i", null) } catch (_: Exception) {} } }
        for (f in futures) { try { f.get(3, TimeUnit.SECONDS) } catch (_: Exception) {} }
        pool.shutdown()
    }

    private fun startNsdDiscovery() {
        stopNsdDiscovery()
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}; override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}; override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(si: NsdServiceInfo) {}
            override fun onServiceFound(si: NsdServiceInfo) {
                nsdManager?.resolveService(si, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(s: NsdServiceInfo) { addServer(s.host?.hostAddress ?: return, s.serviceName) }
                })
            }
        }
        try { nsdManager?.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener) } catch (_: Exception) {}
    }

    private fun stopNsdDiscovery() { try { discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } } catch (_: Exception) {}; discoveryListener = null }

    private fun addServer(ip: String, name: String?) {
        synchronized(foundIps) { if (foundIps.contains(ip)) return; foundIps.add(ip) }
        val dn = name?.takeIf { it.isNotBlank() } ?: try { val r = InetAddress.getByName(ip).canonicalHostName; if (r != ip) r else ip } catch (_: Exception) { ip }
        runOnUiThread {
            if (browseMode != BrowseMode.NETWORK_SERVERS) return@runOnUiThread
            emptyState.visibility = View.GONE
            if (items.any { it.subtitle == ip || it.name == ip }) return@runOnUiThread
            items.add(Item(dn, if (dn != ip) ip else "SMB Server", ItemType.SERVER))
            items.sortBy { it.name.lowercase() }; adapter.notifyDataSetChanged()
        }
    }

    // ── SMB connection ───────────────────────────────────────────────

    private fun connectToServer(server: String) {
        currentServer = server; browseMode = BrowseMode.SMB_SHARES
        items.clear(); adapter.notifyDataSetChanged()
        btnBack.visibility = View.VISIBLE; btnAddServer.visibility = View.VISIBLE
        tvTitle.text = server; tvSubtitle.visibility = View.GONE
        progressBar.visibility = View.VISIBLE; showEmpty("Connecting...")
        executor.execute {
            try {
                disconnectSmb()
                smbClient = SMBClient(SmbConfig.builder().withTimeout(10, TimeUnit.SECONDS).build())
                val ip = try { InetAddress.getByName(server).hostAddress ?: server } catch (_: Exception) { server }
                val conn = smbClient!!.connect(ip)
                val auth = if (currentUser.isNotEmpty()) AuthenticationContext(currentUser, currentPass.toCharArray(), "") else AuthenticationContext.guest()
                smbSession = try { conn.authenticate(auth) } catch (_: Exception) {
                    try { conn.authenticate(AuthenticationContext.anonymous()) } catch (_: Exception) {
                        runOnUiThread { progressBar.visibility = View.GONE; showAuthDialog(server) }; return@execute
                    }
                }
                val shares = try { enumerateShares(ip, auth) } catch (_: Exception) { null }
                val final2 = if (shares.isNullOrEmpty()) probeCommonShares() else shares
                runOnUiThread {
                    progressBar.visibility = View.GONE; emptyState.visibility = View.GONE; items.clear()
                    if (final2.isNotEmpty()) { for (n in final2.sorted()) items.add(Item(n, "", ItemType.SHARE)) }
                    adapter.notifyDataSetChanged(); swipeRefresh.isRefreshing = false
                    if (final2.isEmpty()) showShareNameDialog()
            
                }
            } catch (e: Exception) {
                runOnUiThread { progressBar.visibility = View.GONE; swipeRefresh.isRefreshing = false; showEmpty("Connection failed\n${e.message}") }
            }
        }
    }

    private fun probeCommonShares(): List<String> {
        val found = mutableListOf<String>()
        for (n in listOf("Freebox","Disque dur","share","Share","public","Public","media","Media","videos","Videos","nas","data","Data","home","Home","files","Files","Music","Photos","Documents")) {
            try { val s = smbSession!!.connectShare(n) as? DiskShare; if (s != null) { found.add(n); s.close() } } catch (_: Exception) {}
        }
        return found
    }

    // ── SMB file browsing ────────────────────────────────────────────

    private fun openShare(name: String) {
        progressBar.visibility = View.VISIBLE
        executor.execute {
            try { diskShare?.close(); diskShare = smbSession!!.connectShare(name) as DiskShare; currentShareName = name; currentPath = ""; listSmbDir("") }
            catch (e: Exception) { runOnUiThread { progressBar.visibility = View.GONE; showEmpty("Cannot open share\n${e.message}") } }
        }
    }

    private fun openSmbDir(name: String) {
        progressBar.visibility = View.VISIBLE
        executor.execute { listSmbDir(if (currentPath.isEmpty()) name else "$currentPath\\$name") }
    }

    private fun listSmbDir(path: String) {
        try {
            val dirs = mutableListOf<Item>(); val vids = mutableListOf<Item>()
            for (info: FileIdBothDirectoryInformation in diskShare!!.list(path)) {
                val n = info.fileName; if (n == "." || n == "..") continue
                if ((info.fileAttributes and 0x02L) != 0L) continue
                if ((info.fileAttributes and 0x10L) != 0L) dirs.add(Item(n, "", ItemType.FOLDER))
                else { val ext = n.substringAfterLast('.', "").lowercase(); if (ext in fileExtensions) vids.add(Item(n, formatSize(info.endOfFile), ItemType.VIDEO, info.endOfFile)) }
            }
            dirs.sortBy { it.name.lowercase() }; vids.sortBy { it.name.lowercase() }
            runOnUiThread {
                browseMode = BrowseMode.SMB_FILES; currentPath = path
                items.clear(); items.addAll(dirs); items.addAll(vids); adapter.notifyDataSetChanged()
                progressBar.visibility = View.GONE; swipeRefresh.isRefreshing = false
                btnBack.visibility = View.VISIBLE; btnAddServer.visibility = View.GONE
                tvTitle.text = if (path.isEmpty()) currentShareName else path.substringAfterLast("\\")
                tvSubtitle.text = "\\\\$currentServer\\$currentShareName${if (path.isNotEmpty()) "\\$path" else ""}"; tvSubtitle.visibility = View.VISIBLE
                emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                if (items.isEmpty()) showEmpty("Empty folder")
        
            }
        } catch (e: Exception) { runOnUiThread { progressBar.visibility = View.GONE; swipeRefresh.isRefreshing = false; showEmpty("Error: ${e.message}") } }
    }

    private fun selectSmbFile(fileName: String) {
        saveLastPath()
        val fp = if (currentPath.isEmpty()) fileName else "$currentPath\\$fileName"
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra(RESULT_MODE, "smb")
            putExtra(RESULT_SMB_SERVER, try { InetAddress.getByName(currentServer).hostAddress ?: currentServer } catch (_: Exception) { currentServer })
            putExtra(RESULT_SMB_SHARE, currentShareName); putExtra(RESULT_SMB_PATH, fp)
            putExtra(RESULT_SMB_USER, currentUser); putExtra(RESULT_SMB_PASS, currentPass); putExtra(RESULT_SMB_DOMAIN, "")
        })
        finish()
    }

    // ── Navigation ───────────────────────────────────────────────────

    private fun refresh() {
        when (browseMode) {
            BrowseMode.ROOT -> showRoot(); BrowseMode.LOCAL_FILES -> browseLocalFiles(currentPath)
            BrowseMode.NETWORK_SERVERS -> showNetworkServers(); BrowseMode.SMB_SHARES -> connectToServer(currentServer)
            BrowseMode.SMB_FILES -> { progressBar.visibility = View.VISIBLE; executor.execute { listSmbDir(currentPath) } }
        }
    }

    private fun openFavoriteOrLast(item: Item) {
        val prefs = getSharedPreferences("browser_prefs", MODE_PRIVATE)
        if (item.name.startsWith("↩ ")) {
            val server = prefs.getString("last_server", "") ?: ""
            val share = prefs.getString("last_share", "") ?: ""
            val path = prefs.getString("last_path", "") ?: ""
            val user = prefs.getString("last_user", "") ?: ""
            val pass = prefs.getString("last_pass", "") ?: ""
            if (server.isNotEmpty() && share.isNotEmpty()) {
                currentUser = user; currentPass = pass
                progressBar.visibility = View.VISIBLE
                executor.execute {
                    try {
                        connectSmbDirect(server, share, user, pass)
                        runOnUiThread { listSmbDirAndShow(path) }
                    } catch (e: Exception) {
                        runOnUiThread { progressBar.visibility = View.GONE; Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        } else if (item.name.startsWith("★ ")) {
            val favKey = getFavorites().firstOrNull { fav ->
                val parts = fav.split("|", limit = 5)
                val label = if (parts.size >= 3 && parts[2].isNotEmpty()) parts[2].substringAfterLast("\\") else parts.getOrElse(1) { "" }
                item.name == "★ $label"
            }
            if (favKey != null) {
                val parts = favKey.split("|", limit = 5)
                val server = parts[0]; val share = parts[1]; val path = parts.getOrElse(2) { "" }
                val user = parts.getOrElse(3) { "" }; val pass = parts.getOrElse(4) { "" }
                currentUser = user; currentPass = pass
                progressBar.visibility = View.VISIBLE
                executor.execute {
                    try {
                        connectSmbDirect(server, share, user, pass)
                        runOnUiThread { listSmbDirAndShow(path) }
                    } catch (e: Exception) {
                        runOnUiThread { progressBar.visibility = View.GONE; Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
    }

    private fun goBack() {
        when (browseMode) {
            BrowseMode.LOCAL_FILES -> {
                val p = File(currentPath).parent
                if (p != null && currentPath != Environment.getExternalStorageDirectory().absolutePath) browseLocalFiles(p) else showRoot()
            }
            BrowseMode.NETWORK_SERVERS -> { disconnectSmb(); showRoot() }
            BrowseMode.SMB_SHARES -> { disconnectSmb(); showNetworkServers() }
            BrowseMode.SMB_FILES -> {
                if (currentPath.isNotEmpty()) { progressBar.visibility = View.VISIBLE; executor.execute { listSmbDir(if (currentPath.contains("\\")) currentPath.substringBeforeLast("\\") else "") } }
                else { diskShare?.close(); diskShare = null; connectToServer(currentServer) }
            }
            BrowseMode.ROOT -> finish()
        }
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() { if (browseMode != BrowseMode.ROOT) goBack() else super.onBackPressed() }

    // ── Dialogs ──────────────────────────────────────────────────────

    private fun showAddDialog() {
        val input = EditText(this).apply { hint = if (browseMode == BrowseMode.NETWORK_SERVERS) "IP address or hostname" else "Share name"; setPadding(48, 32, 48, 32) }
        AlertDialog.Builder(this).setTitle(if (browseMode == BrowseMode.NETWORK_SERVERS) "Add Server" else "Add Share").setView(input)
            .setPositiveButton("OK") { _, _ -> val t = input.text.toString().trim(); if (t.isNotEmpty()) { if (browseMode == BrowseMode.NETWORK_SERVERS) connectToServer(t) else openShare(t) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showShareNameDialog() {
        val input = EditText(this).apply { hint = "e.g. Freebox, Videos, Public..."; setPadding(48, 32, 48, 32) }
        AlertDialog.Builder(this).setTitle("Enter share name").setMessage("Could not list shares automatically.").setView(input)
            .setPositiveButton("Open") { _, _ -> val n = input.text.toString().trim(); if (n.isNotEmpty()) openShare(n) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showAuthDialog(server: String) {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val u = EditText(this).apply { hint = "Username"; setText(currentUser) }
        val p = EditText(this).apply { hint = "Password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        l.addView(u); l.addView(p)
        AlertDialog.Builder(this).setTitle("Login to $server").setView(l)
            .setPositiveButton("Connect") { _, _ -> currentUser = u.text.toString().trim(); currentPass = p.text.toString(); connectToServer(server) }
            .setNegativeButton("Cancel", null).show()
    }

    // ── UI helpers ───────────────────────────────────────────────────

    private fun showEmpty(text: String) { tvEmptyText.text = text; emptyState.visibility = View.VISIBLE }
    private fun formatSize(bytes: Long): String = when { bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0); bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0); bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0); else -> "$bytes B" }
    private fun disconnectSmb() { try { diskShare?.close() } catch (_: Exception) {}; try { smbSession?.close() } catch (_: Exception) {}; try { smbClient?.close() } catch (_: Exception) {}; diskShare = null; smbSession = null; smbClient = null }

    override fun onDestroy() { super.onDestroy(); stopNsdDiscovery(); disconnectSmb(); executor.shutdown() }

    // ── Adapter ──────────────────────────────────────────────────────

    inner class ItemAdapter : RecyclerView.Adapter<ItemAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivIcon); val tvName: TextView = view.findViewById(R.id.tvName)
            val tvInfo: TextView = view.findViewById(R.id.tvInfo); val ivChevron: ImageView = view.findViewById(R.id.ivChevron)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(layoutInflater.inflate(R.layout.item_smb_entry, parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]; holder.tvName.text = item.name
            val (iconRes, color) = when (item.type) {
                ItemType.DEVICE -> android.R.drawable.ic_menu_myplaces to 0xFFCE93D8.toInt()
                ItemType.SERVER -> android.R.drawable.ic_menu_share to 0xFF7986CB.toInt()
                ItemType.SHARE -> android.R.drawable.ic_menu_save to 0xFF81C784.toInt()
                ItemType.FOLDER -> android.R.drawable.ic_menu_gallery to 0xFFFFD54F.toInt()
                ItemType.VIDEO -> android.R.drawable.ic_media_play to 0xFFE57373.toInt()
            }
            holder.ivIcon.setImageResource(iconRes); holder.ivIcon.setColorFilter(color)
            if (item.subtitle.isNotEmpty()) { holder.tvInfo.text = item.subtitle; holder.tvInfo.visibility = View.VISIBLE } else holder.tvInfo.visibility = View.GONE
            holder.ivChevron.visibility = if (item.type == ItemType.VIDEO) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener {
                when (item.type) {
                    ItemType.DEVICE -> openLocalFiles()
                    ItemType.SERVER -> { if (browseMode == BrowseMode.ROOT) showNetworkServers() else connectToServer(if (item.subtitle.isNotEmpty() && item.subtitle != "SMB Server") item.subtitle else item.name) }
                    ItemType.SHARE -> {
                        if (browseMode == BrowseMode.ROOT && (item.name.startsWith("★ ") || item.name.startsWith("↩ "))) {
                            openFavoriteOrLast(item)
                        } else {
                            openShare(item.name)
                        }
                    }
                    ItemType.FOLDER -> { if (browseMode == BrowseMode.LOCAL_FILES) browseLocalFiles("$currentPath/${item.name}") else openSmbDir(item.name) }
                    ItemType.VIDEO -> { if (browseMode == BrowseMode.LOCAL_FILES) selectLocalFile(item.name) else selectSmbFile(item.name) }
                }
            }
            // Long press on folders to toggle favorite
            holder.itemView.setOnLongClickListener {
                if (item.type == ItemType.FOLDER && browseMode == BrowseMode.SMB_FILES) {
                    val folderPath = if (currentPath.isEmpty()) item.name else "$currentPath\\${item.name}"
                    toggleFavorite(currentServer, currentShareName, folderPath, currentUser, currentPass)
                    true
                } else false
            }
        }
        override fun getItemCount() = items.size
    }

    // ── Network helpers ──────────────────────────────────────────────

    private fun getLocalIp(): String? {
        try { val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager; val ip = wm.connectionInfo.ipAddress; if (ip != 0) return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}" } catch (_: Exception) {}
        try { for (iface in NetworkInterface.getNetworkInterfaces()) { if (iface.isLoopback || !iface.isUp) continue; for (addr in iface.inetAddresses) { if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress } } } catch (_: Exception) {}
        return null
    }

    private fun getBroadcastAddress(): InetAddress? {
        try { val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager; val d = wm.dhcpInfo; if (d != null && d.ipAddress != 0) { val b = (d.ipAddress and d.netmask) or d.netmask.inv(); return InetAddress.getByAddress(byteArrayOf((b and 0xff).toByte(), (b shr 8 and 0xff).toByte(), (b shr 16 and 0xff).toByte(), (b shr 24 and 0xff).toByte())) } } catch (_: Exception) {}
        try { for (iface in NetworkInterface.getNetworkInterfaces()) { if (iface.isLoopback || !iface.isUp) continue; for (a in iface.interfaceAddresses) { a.broadcast?.let { return it } } } } catch (_: Exception) {}
        val ip = getLocalIp() ?: return null; return try { InetAddress.getByName("${ip.substringBeforeLast('.')}.255") } catch (_: Exception) { null }
    }

    private fun buildNetBIOSQuery(): ByteArray { val p = ByteArray(50); val b = ByteBuffer.wrap(p); b.putShort(1); b.putShort(0x0010); b.putShort(1); b.putShort(0); b.putShort(0); b.putShort(0); b.put(0x20); b.put('C'.code.toByte()); b.put('K'.code.toByte()); for (i in 0 until 30) b.put('A'.code.toByte()); b.put(0); b.putShort(0x0021); b.putShort(1); return p }
    private fun parseNetBIOSResponse(data: ByteArray, length: Int): String? { if (length < 57) return null; return try { String(data.copyOfRange(57, 72)).trim() } catch (_: Exception) { null } }

    // ── SRVSVC ───────────────────────────────────────────────────────

    private fun enumerateShares(host: String, auth: AuthenticationContext): List<String> {
        SMBClient(SmbConfig.builder().withTimeout(10, TimeUnit.SECONDS).build()).use { c -> c.connect(host).use { cn -> cn.authenticate(auth).use { s ->
            val ipc = s.connectShare("IPC\$") as PipeShare; ipc.use {
                val pipe = ipc.open("srvsvc", SMB2ImpersonationLevel.Impersonation, EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, EnumSet.noneOf(SMB2CreateOptions::class.java))
                pipe.use { val br = pipe.transact(buildBindPdu()); if (br.size < 16 || br[2] != 12.toByte()) return emptyList(); return parseShareEnum(pipe.transact(buildShareEnumPdu(host))) }
            }
        } } }
    }

    private fun buildBindPdu(): ByteArray { val s = byteArrayOf(0xc8.toByte(),0x4f,0x32,0x4b,0x70,0x16,0xd3.toByte(),0x01,0x12,0x78,0x5a,0x47,0xbf.toByte(),0x6e,0xe1.toByte(),0x88.toByte()); val n = byteArrayOf(0x04,0x5d,0x88.toByte(),0x8a.toByte(),0xeb.toByte(),0x1c,0xc9.toByte(),0x11,0x9f.toByte(),0xe8.toByte(),0x08,0x00,0x2b,0x10,0x48,0x60); return ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN).apply { put(5);put(0);put(11);put(3);putInt(0x10);putShort(72);putShort(0);putInt(1);putShort(4280);putShort(4280);putInt(0);putShort(1);putShort(0);putShort(0);putShort(1);put(s);putShort(3);putShort(0);put(n);putShort(2);putShort(0) }.array() }

    private fun buildShareEnumPdu(host: String): ByteArray {
        val nm = "\\\\$host"; val ch = nm.toCharArray(); val mc = ch.size + 1
        val sb2 = ByteBuffer.allocate(12 + mc * 2).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(mc); putInt(0); putInt(mc); for (c in ch) putShort(c.code.toShort()); putShort(0) }.array()
        val pd = if (sb2.size % 4 == 0) sb2 else sb2 + ByteArray(4 - sb2.size % 4)
        val st = ByteBuffer.allocate(36 + pd.size).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(0x00020000); put(pd); putInt(1); putInt(1); putInt(0x00020004); putInt(0); putInt(0); putInt(-1); putInt(0) }.array()
        return ByteBuffer.allocate(24 + st.size).order(ByteOrder.LITTLE_ENDIAN).apply { put(5); put(0); put(0); put(3); putInt(0x10); putShort((24 + st.size).toShort()); putShort(0); putInt(2); putShort(st.size.toShort()); putShort(0); putShort(15); put(st) }.array()
    }

    private fun parseShareEnum(data: ByteArray): List<String> {
        if (data.size < 36) return emptyList(); val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); buf.position(24)
        return try { buf.getInt(); buf.getInt(); buf.getInt(); val c = buf.getInt(); buf.getInt(); if (c !in 1..500) return emptyList(); buf.getInt()
            data class F(val np: Int, val t: Int, val rp: Int); val fx = (0 until c).map { F(buf.getInt(), buf.getInt(), buf.getInt()) }; val names = mutableListOf<String>()
            for (f in fx) { if (f.np != 0 && buf.remaining() >= 12) { buf.getInt(); buf.getInt(); val ac = buf.getInt(); if (ac in 1..256 && buf.remaining() >= ac * 2) { val ca = CharArray(ac) { buf.getShort().toInt().toChar() }; val nm = String(ca).trimEnd('\u0000'); val r = buf.position() % 4; if (r != 0 && buf.remaining() >= 4 - r) buf.position(buf.position() + (4 - r)); if (f.rp != 0 && buf.remaining() >= 12) { buf.getInt(); buf.getInt(); val rac = buf.getInt(); if (rac > 0 && buf.remaining() >= rac * 2) { buf.position(buf.position() + rac * 2); val r2 = buf.position() % 4; if (r2 != 0 && buf.remaining() >= 4 - r2) buf.position(buf.position() + (4 - r2)) } }; if (!nm.endsWith("$")) names.add(nm) } } }; names
        } catch (_: Exception) { emptyList() }
    }
}
