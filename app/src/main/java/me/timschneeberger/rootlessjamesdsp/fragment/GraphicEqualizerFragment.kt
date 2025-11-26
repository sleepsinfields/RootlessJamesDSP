package me.timschneeberger.rootlessjamesdsp.fragment

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.Log
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.JdspNative
import me.timschneeberger.rootlessjamesdsp.activity.GraphicEqualizerActivity
import me.timschneeberger.rootlessjamesdsp.adapter.GraphicEqNodeAdapter
import me.timschneeberger.rootlessjamesdsp.contract.AutoEqSelectorContract
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentGraphicEqBinding
import me.timschneeberger.rootlessjamesdsp.model.GraphicEqNode
import me.timschneeberger.rootlessjamesdsp.model.GraphicEqNodeList
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showInputAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showYesNoAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import timber.log.Timber
import java.util.UUID

class GraphicEqualizerFragment : Fragment() {

    private lateinit var binding: FragmentGraphicEqBinding

    private val adapter: GraphicEqNodeAdapter
        get() = binding.nodeList.adapter as GraphicEqNodeAdapter

    // ------------------------------------------------------------------------
    // Stereo M/L/R banks
    // ------------------------------------------------------------------------

    // One node list per stereo bank
private var masterNodes = GraphicEqNodeList()
private var leftNodes   = GraphicEqNodeList()
private var rightNodes  = GraphicEqNodeList()

// Which bank is currently being edited
private enum class CurveBank { MASTER, LEFT, RIGHT }

private var currentBank: CurveBank = CurveBank.MASTER

private fun currentBankKey(): String =
    when (currentBank) {
        CurveBank.MASTER -> PREF_GEQ_MASTER
        CurveBank.LEFT   -> PREF_GEQ_LEFT
        CurveBank.RIGHT  -> PREF_GEQ_RIGHT
    }

private fun geqPrefs() =
    requireContext().getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)

    /** Backup of node currently being edited (if any). */
    private var editorNodeBackup: GraphicEqNode? = null

    /** UUID of node currently being edited (if any). */
    private var editorNodeUuid: UUID? = null

    private var editorActive = false
        set(value) {
            field = value
            binding.add.isEnabled = !value
            binding.reset.isEnabled = !value
            binding.autoeq.isEnabled = !value
            binding.editString.isEnabled = !value
        }



    // ------------------------------------------------------------------------
    // Broadcast & AutoEQ
    // ------------------------------------------------------------------------

   private val autoEqSelectorLauncher =
    registerForActivityResult(AutoEqSelectorContract()) { result ->
        result?.let {
            adapter.nodes.deserialize(it)
            adapter.nodes.sortBy { node -> node.freq }
            binding.equalizerSurface.setNodes(adapter.nodes)

            // NEW:
            storeCurrentBankNodes()
            save()
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Constants.ACTION_PRESET_LOADED -> {
                    activity?.finish()
                    startActivity(
                        Intent(requireContext(), GraphicEqualizerActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        requireContext().registerLocalReceiver(
            broadcastReceiver,
            IntentFilter(Constants.ACTION_PRESET_LOADED)
        )
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        requireContext().unregisterLocalReceiver(broadcastReceiver)
        super.onDestroy()
    }

override fun onCreateView(
inflater: LayoutInflater,
container: ViewGroup?,
savedInstanceState: Bundle?,
): View {
Log.e("StereoEQ", "GraphicEqFragment created")
binding = FragmentGraphicEqBinding.inflate(layoutInflater, container, false)

// Restore saved switch states  
val prefs = requireContext().getSharedPreferences(PREF_STEREO_FLAGS, Context.MODE_PRIVATE)  

val savedMaster = prefs.getBoolean(KEY_MASTER, true)  
val savedLeft   = prefs.getBoolean(KEY_LEFT, false)  
val savedRight  = prefs.getBoolean(KEY_RIGHT, false)  

// Push restored UI state to switches  
binding.switchArbEqMaster.isChecked = savedMaster  
binding.switchArbEqLeft.isChecked   = savedLeft  
binding.switchArbEqRight.isChecked  = savedRight  

// Push restored values to DSP  
val global = savedMaster || savedLeft || savedRight  
try {  
    JdspNative.setStereoArbEqFlags(global, savedMaster, savedLeft, savedRight)  
} catch (e: Throwable) {  
    Log.e("StereoEQ", "Could not restore stereo flags", e)  
}  

// Preview card collapse / expand  
binding.previewCard.setOnClickListener {  
    if (resources.configuration.orientation != ORIENTATION_LANDSCAPE) {  
        val newState = !binding.equalizerSurface.isVisible  
        collapsePreview(newState)  
    }  
}  

  
    // Reset button  
    binding.reset.setOnClickListener {
    requireContext().showYesNoAlert(
        R.string.geq_reset_confirm_title,
        R.string.geq_reset_confirm,
    ) { yes ->
        if (yes) {
            adapter.nodes.deserialize(Constants.DEFAULT_GEQ)
            adapter.nodes.sortBy { node -> node.freq }
            binding.equalizerSurface.setNodes(adapter.nodes)
            editorDiscard()
            updateViewState()

            // NEW:
            storeCurrentBankNodes()
            save()
        }
    }
}

    // Edit-as-string  
    binding.editString.setOnClickListener {
    requireContext().showInputAlert(
        layoutInflater,
        R.string.geq_edit_as_string,
        R.string.geq_edit_hint,
        adapter.nodes.serialize(),
        false,
        null
    ) { text ->
        text?.let {
            adapter.nodes.deserialize(it)
            adapter.nodes.sortBy { node -> node.freq }
            binding.equalizerSurface.setNodes(adapter.nodes)
        }

        // NEW:
        storeCurrentBankNodes()
        save()
    }
}

    // Add node  
    binding.add.setOnClickListener {  
        if (editorActive) return@setOnClickListener  

        editorNodeBackup = null  
        editorNodeUuid = null  
        editorActive = true  

        binding.freqInput.value = 100f  
        binding.gainInput.value = 0f  
        updateViewState()  
    }  

    binding.freqInput.setOnValueChangedListener { editorApply() }  
    binding.gainInput.setOnValueChangedListener { editorApply() }  

    binding.freqInput.customStepScale = { value: Float, _: Boolean ->  
        when (value) {  
            in 0f..400f -> 10f  
            in 400f..600f -> 20f  
            in 600f..1000f -> 50f  
            in 1000f..5000f -> 100f  
            in 5000f..Float.MAX_VALUE -> 500f  
            else -> 10f  
        }  
    }  

    binding.confirm.setOnClickListener { editorSave() }  
    binding.cancel.setOnClickListener { editorDiscard() }  

    binding.autoeq.setOnClickListener {  
        editorDiscard()  
        autoEqSelectorLauncher.launch(0)  
    }  

// --- Stereo arbitrary EQ switches: listeners only ---  
binding.switchArbEqMaster.setOnCheckedChangeListener { _, _ ->  
    updateStereoArbEqFlags()  
}  
binding.switchArbEqLeft.setOnCheckedChangeListener { _, _ ->  
    updateStereoArbEqFlags()  
}  
binding.switchArbEqRight.setOnCheckedChangeListener { _, _ ->  
    updateStereoArbEqFlags()  
}  

// Long-press to choose which bank is being edited  
binding.switchArbEqMaster.setOnLongClickListener {  
    switchBank(CurveBank.MASTER)  
    true  
}  
binding.switchArbEqLeft.setOnLongClickListener {  
    switchBank(CurveBank.LEFT)  
    true  
}  
binding.switchArbEqRight.setOnLongClickListener {  
    switchBank(CurveBank.RIGHT)  
    true  
}  

// Node list setup + load  
binding.nodeList.layoutManager = LinearLayoutManager(requireContext())  
loadNodes(savedInstanceState)  

updateViewState()  
return binding.root

}

  // ------------------------------------------------------------------------
// Stereo bank helpers
// ------------------------------------------------------------------------

private fun initBanksFromLegacy(nodes: GraphicEqNodeList) {
    masterNodes = GraphicEqNodeList().apply { addAll(nodes) }
    leftNodes   = GraphicEqNodeList().apply { addAll(nodes) }
    rightNodes  = GraphicEqNodeList().apply { addAll(nodes) }

    currentBank = CurveBank.MASTER
    Log.e(
        "StereoEQ",
        "initBanksFromLegacy: size=${nodes.size} cloned to M/L/R"
    )
}

private fun storeCurrentBankNodes() {
    when (currentBank) {
        CurveBank.MASTER -> {
            masterNodes.clear()
            masterNodes.addAll(adapter.nodes)
            Log.e("StereoEQ", "storeCurrentBankNodes: MASTER size=${masterNodes.size}")
        }
        CurveBank.LEFT -> {
            leftNodes.clear()
            leftNodes.addAll(adapter.nodes)
            Log.e("StereoEQ", "storeCurrentBankNodes: LEFT size=${leftNodes.size}")
        }
        CurveBank.RIGHT -> {
            rightNodes.clear()
            rightNodes.addAll(adapter.nodes)
            Log.e("StereoEQ", "storeCurrentBankNodes: RIGHT size=${rightNodes.size}")
        }
    }
}

@SuppressLint("NotifyDataSetChanged")
private fun loadBankNodes(target: CurveBank) {
    val src = when (target) {
        CurveBank.MASTER -> {
            Log.e("StereoEQ", "loadBankNodes: MASTER size=${masterNodes.size}")
            masterNodes
        }
        CurveBank.LEFT -> {
            Log.e("StereoEQ", "loadBankNodes: LEFT size=${leftNodes.size}")
            leftNodes
        }
        CurveBank.RIGHT -> {
            Log.e("StereoEQ", "loadBankNodes: RIGHT size=${rightNodes.size}")
            rightNodes
        }
    }

    adapter.nodes.clear()
    adapter.nodes.addAll(src)
    adapter.nodes.sortBy { it.freq }
    adapter.notifyDataSetChanged()
    binding.equalizerSurface.setNodes(adapter.nodes)
}

@SuppressLint("NotifyDataSetChanged")
private fun switchBank(target: CurveBank) {
    if (target == currentBank) {
        Log.e("StereoEQ", "switchBank: target == current; ignoring")
        return
    }

    // 1) Save current visible UI into the current bank list
    storeCurrentBankNodes()

    // 2) Change active bank
    currentBank = target

    // 3) Load target bank into adapter/UI
    loadBankNodes(target)

    // 4) Refresh labels like "(Master)/(Left)/(Right)"
    updateViewState()

    Log.e("StereoEQ", "switchBank → now editing $target")
}
    // ------------------------------------------------------------------------
// Node loading
// ------------------------------------------------------------------------
private fun loadNodes(savedInstanceState: Bundle?) {
    val prefs = geqPrefs()

    val nodesForAdapter = GraphicEqNodeList()
    val dataSaved = savedInstanceState?.getBundle(STATE_NODES)

    if (dataSaved != null) {
        // Instance-state restore path
        nodesForAdapter.fromBundle(dataSaved)
        initBanksFromLegacy(nodesForAdapter)
        Log.e("StereoEQ", "loadNodes: restored from instanceState, size=${nodesForAdapter.size}")
    } else {
        // Try new per-bank storage first
        val masterStr = prefs.getString(PREF_GEQ_MASTER, null)
        val leftStr   = prefs.getString(PREF_GEQ_LEFT,   null)
        val rightStr  = prefs.getString(PREF_GEQ_RIGHT,  null)

        if (!masterStr.isNullOrEmpty() ||
            !leftStr.isNullOrEmpty()   ||
            !rightStr.isNullOrEmpty()
        ) {
          fun parseOrEmpty(str: String?): GraphicEqNodeList =
    GraphicEqNodeList().apply {
        if (!str.isNullOrEmpty()) {
            deserialize(str)
        }
    }

// Use master as canonical; if left/right missing OR empty → fallback to master
val masterSrc = masterStr
val leftSrc   = if (leftStr.isNullOrEmpty()) masterStr else leftStr
val rightSrc  = if (rightStr.isNullOrEmpty()) masterStr else rightStr

masterNodes = parseOrEmpty(masterSrc)
leftNodes   = parseOrEmpty(leftSrc)
rightNodes  = parseOrEmpty(rightSrc)

            currentBank = CurveBank.MASTER
            nodesForAdapter.addAll(masterNodes)

            Log.e(
                "StereoEQ",
                "loadNodes: restored banks M=${masterNodes.size} " +
                    "L=${leftNodes.size} R=${rightNodes.size}"
            )
        } else {
            // Full fallback: legacy single-curve string
            val legacyString = prefs.getString(
                getString(R.string.key_geq_nodes),
                Constants.DEFAULT_GEQ
            )!!

            nodesForAdapter.deserialize(legacyString)
            initBanksFromLegacy(nodesForAdapter)

            Log.e(
                "StereoEQ",
                "loadNodes: legacy curve loaded (${nodesForAdapter.size} nodes) " +
                    "and cloned to all banks"
            )
        }
    }

    nodesForAdapter.sortBy { it.freq }

val nodeAdapter = GraphicEqNodeAdapter(nodesForAdapter).apply {
    onItemsChanged = {
        binding.equalizerSurface.setNodes(it.nodes)
        updateViewState()

        // 🔴 This was missing:
        storeCurrentBankNodes()   // sync visible nodes → current bank list

        save()                   // serialize all banks → prefs (MUST be last)
    }

    onItemClicked = { node: GraphicEqNode, _: Int ->
        editorNodeBackup = node
        editorNodeUuid = node.uuid
        editorActive = true

        binding.freqInput.value = node.freq.toFloat()
        binding.gainInput.value = node.gain.toFloat()
        updateViewState()
    }
}

binding.nodeList.adapter = nodeAdapter
binding.equalizerSurface.setNodes(nodeAdapter.nodes)
}
    // ------------------------------------------------------------------------
    // UI state helpers
    // ------------------------------------------------------------------------

    private fun updateViewState() {
        val empty = adapter.nodes.isEmpty()
        binding.emptyView.isVisible = empty
        binding.nodeList.isVisible = !empty && !editorActive
        binding.nodeEdit.isVisible = editorActive

        binding.nodeDetailContextButtons.visibility =
            if (editorActive) View.VISIBLE else View.INVISIBLE

        val bankSuffix = when (currentBank) {
            CurveBank.MASTER -> " (Master)"
            CurveBank.LEFT   -> " (Left)"
            CurveBank.RIGHT  -> " (Right)"
        }

        val baseTitle = if (editorActive) {
            getString(R.string.geq_node_editor)
        } else {
            getString(R.string.geq_node_list)
        }

        binding.editCardTitle.text = baseTitle + bankSuffix
    }

  private fun updateStereoArbEqFlags() {
    val master = binding.switchArbEqMaster.isChecked
    val left   = binding.switchArbEqLeft.isChecked
    val right  = binding.switchArbEqRight.isChecked

    val global = master || left || right

    // 1) Persist to SharedPreferences
    val prefs = requireContext().getSharedPreferences(PREF_STEREO_FLAGS, Context.MODE_PRIVATE)
    prefs.edit()
        .putBoolean(KEY_MASTER, master)
        .putBoolean(KEY_LEFT, left)
        .putBoolean(KEY_RIGHT, right)
        .apply()

    // 2) Log for sanity
    Log.e(
        "StereoEQ",
        "UI switches changed global=$global master=$master left=$left right=$right"
    )

    // 3) Push to DSP
    try {
        JdspNative.setStereoArbEqFlags(global, master, left, right)
    } catch (e: UnsatisfiedLinkError) {
        Log.e("StereoEQ", "Failed to call setStereoArbEqFlags JNI", e)
    }
}

    // ------------------------------------------------------------------------
    // Editor logic
    // ------------------------------------------------------------------------

    override fun onStop() {
        if (editorActive) {
            Timber.d("onStop: discarding unsaved changes")
            editorDiscard()
        }
        super.onStop()
    }

    private fun editorCanSave(): Boolean {
        // Allow save when all values are valid
        val freqValid = binding.freqInput.isCurrentValueValid()
        val gainValid = binding.gainInput.isCurrentValueValid()
        return freqValid && gainValid
    }

    private fun editorApply() {
        if (editorCanSave()) {
            val uuid = editorNodeUuid
            val freq = binding.freqInput.value.toDouble()
            val gain = binding.gainInput.value.toDouble()

            if (uuid == null) {
                val node = GraphicEqNode(freq, gain)
                adapter.nodes.add(node)
                editorNodeUuid = node.uuid
                Timber.d(
                    "editorApply: tracking new added node $editorNodeUuid " +
                        "for $freq Hz with $gain dB (source: editorApply/add)"
                )
            } else {
                Timber.d("editorApply: modifying node $editorNodeUuid")
                val index = adapter.nodes.indexOfFirst { it.uuid == uuid }
                if (index < 0) {
                    Timber.e("editorApply: failed to find matching node UUID")
                } else {
                    Timber.d(
                        "tracking node UUID $uuid (unchanged) for $freq Hz with $gain dB " +
                            "(source: editorApply/modify)"
                    )
                    adapter.nodes[index] = GraphicEqNode(freq, gain, uuid)
                }
            }
        }
    }

    private fun editorDiscard() {
        val uuid = editorNodeUuid
        if (editorNodeBackup != null && uuid != null) {
            // Revert edits to node
            Timber.d("editorDiscard: reverting modifications to node $uuid")
            val index = adapter.nodes.indexOfFirst { it.uuid == uuid }
            if (index < 0) {
                Timber.e("editorDiscard: failed to find matching node UUID")
            } else {
                adapter.nodes[index] = editorNodeBackup
            }
        } else if (uuid != null) {
            // Revert added node
            Timber.d("editorDiscard: reverting addition of node $uuid")
            adapter.nodes.removeAll { it.uuid == uuid }
        }

        editorNodeBackup = null
        editorNodeUuid = null
        editorActive = false
        updateViewState()
    }

@SuppressLint("NotifyDataSetChanged")
private fun editorSave() {
    if (!editorCanSave()) {
        requireContext().showYesNoAlert(
            R.string.geq_discard_changes_title,
            R.string.geq_discard_changes
        ) { yes ->
            if (yes) {
                editorDiscard()
            }
        }
        return
    }

    Timber.d("editorSave: confirming changes to node $editorNodeUuid")
    editorNodeBackup = null
    editorNodeUuid = null
    editorActive = false

    adapter.nodes.sortBy { it.freq }
    adapter.notifyDataSetChanged()

    updateViewState()

    // NEW: sync current bank + persist
    storeCurrentBankNodes()
    save()
}
 
    // ------------------------------------------------------------------------
    // Misc (orientation, save)
    // ------------------------------------------------------------------------

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (newConfig.orientation == ORIENTATION_LANDSCAPE) {
            collapsePreview(false)
        }
        super.onConfigurationChanged(newConfig)
    }

    private fun collapsePreview(collapsed: Boolean) {
        binding.equalizerSurface.isVisible = collapsed
        binding.previewTitle.text =
            getString(if (collapsed) R.string.geq_preview else R.string.geq_preview_collapsed)
    }
    
@SuppressLint("ApplySharedPref")
private fun save() {
    val prefs = geqPrefs()

    // Bank lists are already in masterNodes / leftNodes / rightNodes.
    // Just serialize them.
    val masterStr = masterNodes.serialize()
    val leftStr   = leftNodes.serialize()
    val rightStr  = rightNodes.serialize()

    // Legacy: keep writing a single curve as well (use MASTER as canonical)
    val legacyStr = masterStr

    prefs.edit()
        .putString(getString(R.string.key_geq_nodes), legacyStr)
        .putString(PREF_GEQ_MASTER, masterStr)
        .putString(PREF_GEQ_LEFT,   leftStr)
        .putString(PREF_GEQ_RIGHT,  rightStr)
        .commit()

    requireContext().sendLocalBroadcast(Intent(Constants.ACTION_GRAPHIC_EQ_CHANGED))
}
    override fun onSaveInstanceState(outState: Bundle) {
        // TODO workaround: discard changes
        if (editorActive)
            editorDiscard()

        /*super.onSaveInstanceState(outState.apply {
            putBundle(STATE_NODES, adapter.nodes.toBundle())
            putSerializable(STATE_EDITOR_NODE_UUID, editorNodeUuid)
            putSerializable(STATE_EDITOR_NODE_BACKUP, editorNodeBackup)
            putBoolean(STATE_EDITOR_ACTIVE, editorActive)
            putFloat(STATE_EDITOR_UI_FREQ_INPUT, binding.freqInput.value)
            putFloat(STATE_EDITOR_UI_GAIN_INPUT, binding.gainInput.value)
        })*/
    }

    companion object {
    const val STATE_NODES = "nodes"
    const val STATE_EDITOR_NODE_UUID = "editorNodeUuid"
    const val STATE_EDITOR_NODE_BACKUP = "editorNodeBackup"
    const val STATE_EDITOR_ACTIVE = "editorActive"
    const val STATE_EDITOR_UI_FREQ_INPUT = "editorUiFreqInput"
    const val STATE_EDITOR_UI_GAIN_INPUT = "editorUiGainInput"

    // New: where we persist the stereo switches (master/left/right flags)
    private const val PREF_STEREO_FLAGS = "stereo_geq_flags"
    private const val KEY_MASTER = "flag_master"
    private const val KEY_LEFT   = "flag_left"
    private const val KEY_RIGHT  = "flag_right"

    // New: per-bank stored curves (SharedPreferences keys)
    const val PREF_GEQ_MASTER = "geq_nodes_master"
    const val PREF_GEQ_LEFT   = "geq_nodes_left"
    const val PREF_GEQ_RIGHT  = "geq_nodes_right"

    fun newInstance(): GraphicEqualizerFragment {
        return GraphicEqualizerFragment()
    }
}
}