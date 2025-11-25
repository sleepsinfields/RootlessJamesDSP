package me.timschneeberger.rootlessjamesdsp.fragment

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

    // --- Stereo M / L / R banks (in-memory lists) ---
    private var masterNodes = GraphicEqNodeList()
    private var leftNodes   = GraphicEqNodeList()
    private var rightNodes  = GraphicEqNodeList()

    private enum class CurveBank { MASTER, LEFT, RIGHT }
    private var currentBank: CurveBank = CurveBank.MASTER

    /** editorNodeBackup contains a backup of the node loaded in the editor. */
    private var editorNodeBackup: GraphicEqNode? = null

    /** editorNodeUuid contains the UUID of the node loaded in the editor. */
    private var editorNodeUuid: UUID? = null

    private var editorActive = false
        set(value) {
            field = value
            binding.add.isEnabled = !value
            binding.reset.isEnabled = !value
            binding.autoeq.isEnabled = !value
            binding.editString.isEnabled = !value
        }

    private val autoEqSelectorLauncher =
        registerForActivityResult(AutoEqSelectorContract()) { result ->
            result?.let {
                // Replace nodes with AutoEQ curve for current bank
                adapter.nodes.deserialize(it)
                adapter.nodes.sortBy { node -> node.freq }
                adapter.notifyDataSetChanged()
                binding.equalizerSurface.setNodes(adapter.nodes)
                storeCurrentBankNodes()
                save()
                updateViewState()
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

        // Preview card toggle
        binding.previewCard.setOnClickListener {
            if (resources.configuration.orientation != ORIENTATION_LANDSCAPE) {
                val newState = !binding.equalizerSurface.isVisible
                collapsePreview(newState)
            }
        }

        // Reset button: reset current bank to default
        binding.reset.setOnClickListener {
            requireContext().showYesNoAlert(
                R.string.geq_reset_confirm_title,
                R.string.geq_reset_confirm,
            ) { yes ->
                if (yes) {
                    adapter.nodes.deserialize(Constants.DEFAULT_GEQ)
                    adapter.nodes.sortBy { it.freq }
                    adapter.notifyDataSetChanged()
                    binding.equalizerSurface.setNodes(adapter.nodes)

                    editorDiscard()
                    storeCurrentBankNodes()
                    save()
                    updateViewState()
                }
            }
        }

        // Edit as string
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
                    adapter.notifyDataSetChanged()
                    binding.equalizerSurface.setNodes(adapter.nodes)
                    storeCurrentBankNodes()
                    save()
                    updateViewState()
                }
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

        // AutoEQ selector
        binding.autoeq.setOnClickListener {
            editorDiscard()
            autoEqSelectorLauncher.launch(0)
        }

        // --- Stereo switches: default state + listeners ---
        binding.apply {
            switchArbEqMaster.isChecked = true
            switchArbEqLeft.isChecked   = false
            switchArbEqRight.isChecked  = false
        }

        binding.switchArbEqMaster.setOnCheckedChangeListener { _, _ ->
            updateStereoArbEqFlags()
        }
        binding.switchArbEqLeft.setOnCheckedChangeListener { _, _ ->
            updateStereoArbEqFlags()
        }
        binding.switchArbEqRight.setOnCheckedChangeListener { _, _ ->
            updateStereoArbEqFlags()
        }

        // Long-press: select which bank to edit (Master / Left / Right)
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

        // Load initial nodes from prefs / saved state
        binding.nodeList.layoutManager = LinearLayoutManager(requireContext())
        loadNodes(savedInstanceState)

        updateViewState()
        return binding.root
    }

    // --- Bank initialization & switching helpers ---

    /**
     * Called only in the legacy single-curve case.
     * Takes adapter.nodes and clones them into M/L/R banks.
     */
    private fun initStereoBanksFromCurrent() {
        val base = GraphicEqNodeList().apply { addAll(adapter.nodes) }

        masterNodes = GraphicEqNodeList().apply { addAll(base) }
        leftNodes   = GraphicEqNodeList().apply { addAll(base) }
        rightNodes  = GraphicEqNodeList().apply { addAll(base) }

        currentBank = CurveBank.MASTER

        adapter.nodes.clear()
        adapter.nodes.addAll(masterNodes)
        adapter.nodes.sortBy { it.freq }
        binding.equalizerSurface.setNodes(adapter.nodes)
    }

    private fun storeCurrentBankNodes() {
        when (currentBank) {
            CurveBank.MASTER -> {
                masterNodes.clear()
                masterNodes.addAll(adapter.nodes)
            }
            CurveBank.LEFT -> {
                leftNodes.clear()
                leftNodes.addAll(adapter.nodes)
            }
            CurveBank.RIGHT -> {
                rightNodes.clear()
                rightNodes.addAll(adapter.nodes)
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadBankNodes(target: CurveBank) {
        val src = when (target) {
            CurveBank.MASTER -> masterNodes
            CurveBank.LEFT   -> leftNodes
            CurveBank.RIGHT  -> rightNodes
        }

        adapter.nodes.clear()
        adapter.nodes.addAll(src)
        adapter.nodes.sortBy { it.freq }
        adapter.notifyDataSetChanged()
        binding.equalizerSurface.setNodes(adapter.nodes)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun switchBank(target: CurveBank) {
        // Commit any in-progress edit to current bank before switching
        if (editorActive) {
            editorSave()
        }

        // Save current adapter state into the bank we’re leaving
        storeCurrentBankNodes()

        // Switch bank
        currentBank = target

        // Load that bank’s nodes into the adapter
        loadBankNodes(target)

        updateViewState()
    }

    // --- Load initial nodes from prefs / saved state ---

    private fun loadNodes(savedInstanceState: Bundle?) {
        val prefs = requireContext()
            .getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)

        // Try new per-bank storage first
        val masterString = prefs.getString(PREF_KEY_GEQ_MASTER, null)
        val leftString   = prefs.getString(PREF_KEY_GEQ_LEFT, null)
        val rightString  = prefs.getString(PREF_KEY_GEQ_RIGHT, null)

        val nodes = GraphicEqNodeList()

        if (savedInstanceState != null) {
            // (State restore is still effectively disabled in upstream; keep behavior)
            val dataSaved = savedInstanceState.getBundle(STATE_NODES)
            if (dataSaved != null) {
                nodes.fromBundle(dataSaved)
            } else {
                val singleString = prefs.getString(
                    getString(R.string.key_geq_nodes),
                    Constants.DEFAULT_GEQ
                )!!
                nodes.deserialize(singleString)
            }

            nodes.sortBy { it.freq }
            binding.equalizerSurface.setNodes(nodes)

            binding.nodeList.adapter = GraphicEqNodeAdapter(nodes).apply {
                configureAdapterCallbacks(this)
            }

            // Legacy-style init
            initStereoBanksFromCurrent()
            return
        }

        if (masterString != null || leftString != null || rightString != null) {
            // --- New per-bank format present ---
            val baseString = masterString
                ?: leftString
                ?: rightString
                ?: prefs.getString(
                    getString(R.string.key_geq_nodes),
                    Constants.DEFAULT_GEQ
                )!!

            masterNodes = GraphicEqNodeList().apply {
                deserialize(masterString ?: baseString)
            }
            leftNodes = GraphicEqNodeList().apply {
                deserialize(leftString ?: baseString)
            }
            rightNodes = GraphicEqNodeList().apply {
                deserialize(rightString ?: baseString)
            }

            nodes.addAll(masterNodes) // show master by default
            nodes.sortBy { it.freq }
            binding.equalizerSurface.setNodes(nodes)

            binding.nodeList.adapter = GraphicEqNodeAdapter(nodes).apply {
                configureAdapterCallbacks(this)
            }

            currentBank = CurveBank.MASTER
        } else {
            // --- Legacy single-curve format (first run) ---
            val nodeString = prefs.getString(
                getString(R.string.key_geq_nodes),
                Constants.DEFAULT_GEQ
            )!!
            nodes.deserialize(nodeString)
            nodes.sortBy { it.freq }
            binding.equalizerSurface.setNodes(nodes)

            binding.nodeList.adapter = GraphicEqNodeAdapter(nodes).apply {
                configureAdapterCallbacks(this)
            }

            // Clone into M/L/R for this session
            initStereoBanksFromCurrent()
        }
    }

    // Configure adapter callbacks (shared between both load paths)
    private fun configureAdapterCallbacks(adapterInstance: GraphicEqNodeAdapter) {
        adapterInstance.onItemsChanged = {
            // keep equalizer surface in sync
            binding.equalizerSurface.setNodes(it.nodes)

            // sync current bank
            adapter.nodes.sortBy { node -> node.freq }
            storeCurrentBankNodes()

            updateViewState()
            save()
        }

        adapterInstance.onItemClicked = { node: GraphicEqNode, _: Int ->
            editorNodeBackup = node
            editorNodeUuid = node.uuid
            editorActive = true

            binding.freqInput.value = node.freq.toFloat()
            binding.gainInput.value = node.gain.toFloat()
            updateViewState()
        }
    }

    // --- View / title state ---

    private fun updateViewState() {
        val empty = adapter.nodes.isEmpty()
        binding.emptyView.isVisible = empty
        binding.nodeList.isVisible = !empty && !editorActive
        binding.nodeEdit.isVisible = editorActive

        binding.nodeDetailContextButtons.visibility =
            if (editorActive) View.VISIBLE else View.INVISIBLE

        val baseTitle = if (editorActive) {
            getString(R.string.geq_node_editor)
        } else {
            getString(R.string.geq_node_list)
        }

        val suffix = when (currentBank) {
            CurveBank.MASTER -> " (Master)"
            CurveBank.LEFT   -> " (Left)"
            CurveBank.RIGHT  -> " (Right)"
        }

        binding.editCardTitle.text = baseTitle + suffix
    }

    // --- Stereo flags -> native layer ---

    private fun updateStereoArbEqFlags() {
        val master = binding.switchArbEqMaster.isChecked
        val left   = binding.switchArbEqLeft.isChecked
        val right  = binding.switchArbEqRight.isChecked

        val global = master || left || right

        Log.e(
            "StereoEQ",
            "UI switches changed global=$global master=$master left=$left right=$right"
        )

        try {
            JdspNative.setStereoArbEqFlags(global, master, left, right)
        } catch (e: UnsatisfiedLinkError) {
            Log.e("StereoEQ", "Failed to call setStereoArbEqFlags JNI", e)
        }
    }

    override fun onStop() {
        if (editorActive) {
            Timber.d("onStop: discarding unsaved changes")
            editorDiscard()
        }
        super.onStop()
    }

    // --- Editor logic ---

    private fun editorCanSave(): Boolean {
        val freqValid = binding.freqInput.isCurrentValueValid()
        val gainValid = binding.gainInput.isCurrentValueValid()
        return freqValid && gainValid
    }

    private fun editorApply() {
        if (!editorCanSave()) return

        val uuid = editorNodeUuid
        val freq = binding.freqInput.value.toDouble()
        val gain = binding.gainInput.value.toDouble()

        if (uuid == null) {
            val node = GraphicEqNode(freq, gain)
            adapter.nodes.add(node)
            editorNodeUuid = node.uuid
            Timber.d(
                "editorApply: tracking new node $editorNodeUuid for $freq Hz with $gain dB (source: editorApply/add)"
            )
        } else {
            Timber.d("editorApply: modifying node $editorNodeUuid")
            val index = adapter.nodes.indexOfFirst { it.uuid == uuid }
            if (index < 0) {
                Timber.e("editorApply: failed to find matching node UUID")
            } else {
                Timber.d(
                    "tracking node UUID $uuid (unchanged) for $freq Hz with $gain dB (source: editorApply/modify)"
                )
                adapter.nodes[index] = GraphicEqNode(freq, gain, uuid)
            }
        }
    }

    private fun editorDiscard() {
        val uuid = editorNodeUuid
        if (editorNodeBackup != null && uuid != null) {
            // Revert edits to existing node
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

        // Keep bank in sync with adapter
        storeCurrentBankNodes()

        updateViewState()
    }

    // --- Orientation / preview ---

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

    // --- Persist + broadcast: save all three banks independently ---

    @SuppressLint("ApplySharedPref")
    private fun save() {
        // Make sure current bank is stored
        storeCurrentBankNodes()

        val prefs = requireContext()
            .getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)

        val masterString = masterNodes.serialize()
        val leftString   = leftNodes.serialize()
        val rightString  = rightNodes.serialize()

        prefs.edit()
            // New per-bank storage
            .putString(PREF_KEY_GEQ_MASTER, masterString)
            .putString(PREF_KEY_GEQ_LEFT, leftString)
            .putString(PREF_KEY_GEQ_RIGHT, rightString)
            // Legacy single-curve key: keep master for compatibility
            .putString(getString(R.string.key_geq_nodes), masterString)
            .commit()

        requireContext().sendLocalBroadcast(Intent(Constants.ACTION_GRAPHIC_EQ_CHANGED))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // TODO workaround: discard changes
        if (editorActive)
            editorDiscard()

        // Upstream had this disabled; leave it off for now.
        /*
        super.onSaveInstanceState(outState.apply {
            putBundle(STATE_NODES, adapter.nodes.toBundle())
            putSerializable(STATE_EDITOR_NODE_UUID, editorNodeUuid)
            putSerializable(STATE_EDITOR_NODE_BACKUP, editorNodeBackup)
            putBoolean(STATE_EDITOR_ACTIVE, editorActive)
            putFloat(STATE_EDITOR_UI_FREQ_INPUT, binding.freqInput.value)
            putFloat(STATE_EDITOR_UI_GAIN_INPUT, binding.gainInput.value)
        })
        */
    }

    companion object {
        const val STATE_NODES = "nodes"
        const val STATE_EDITOR_NODE_UUID = "editorNodeUuid"
        const val STATE_EDITOR_NODE_BACKUP = "editorNodeBackup"
        const val STATE_EDITOR_ACTIVE = "editorActive"
        const val STATE_EDITOR_UI_FREQ_INPUT = "editorUiFreqInput"
        const val STATE_EDITOR_UI_GAIN_INPUT = "editorUiGainInput"

        // New per-bank preference keys
        private const val PREF_KEY_GEQ_MASTER = "geq_nodes_master"
        private const val PREF_KEY_GEQ_LEFT   = "geq_nodes_left"
        private const val PREF_KEY_GEQ_RIGHT  = "geq_nodes_right"

        fun newInstance(): GraphicEqualizerFragment {
            return GraphicEqualizerFragment()
        }
    }
}