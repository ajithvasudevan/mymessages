package dev.ajithv.mymessages

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch

/** One device row: name + live power + a HA-style toggle. */
data class Device(
    val name: String,
    val entityId: String,
    val domain: String,
    var on: Boolean,
    var power: String
)

class DeviceAdapter(private val onToggle: (Device) -> Unit) :
    RecyclerView.Adapter<DeviceAdapter.VH>() {

    private val items = mutableListOf<Device>()

    /** The frozen row set for this session (entity_ids of the first render). */
    val entityIds: List<String> get() = items.map { it.entityId }

    /** Establish the row set (first render). */
    fun submit(list: List<Device>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /**
     * Live refresh that keeps the frozen row set. HA re-publishes only the currently-ON
     * devices, so a frozen device that is now absent has been switched off -> mark it OFF
     * (and ~0 W) but keep its row. New devices not in the frozen set are ignored. This is
     * what makes a toggled-off row stay put instead of vanishing.
     */
    fun merge(incoming: List<Device>) {
        val byId = incoming.associateBy { it.entityId }
        items.forEachIndexed { i, cur ->
            val inc = byId[cur.entityId]
            val newOn = inc?.on ?: false
            val newPower = inc?.power ?: "0 W"
            if (cur.on != newOn || cur.power != newPower) {
                cur.on = newOn
                cur.power = newPower
                notifyItemChanged(i)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.row_device, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val name: TextView = v.findViewById(R.id.dev_name)
        private val power: TextView = v.findViewById(R.id.dev_power)
        private val sw: MaterialSwitch = v.findViewById(R.id.dev_switch)

        fun bind(d: Device) {
            name.text = d.name
            power.text = d.power
            // No OnCheckedChangeListener -> setting isChecked here never fires a publish.
            sw.isChecked = d.on
            sw.setOnClickListener {
                d.on = sw.isChecked          // click already flipped the visual state
                onToggle(d)
            }
        }
    }
}
