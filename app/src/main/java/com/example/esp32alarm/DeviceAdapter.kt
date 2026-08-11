package com.example.esp32alarm

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.esp32alarm.model.DeviceItem

class DeviceAdapter(
    private var items: List<DeviceItem>,
    private val onItemClick: (DeviceItem) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvDeviceName)
        val tvAddress: TextView = itemView.findViewById(R.id.tvDeviceAddress)
        val tvRssi: TextView = itemView.findViewById(R.id.tvDeviceRssi)
        val tvStatus: TextView = itemView.findViewById(R.id.tvDeviceStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name ?: "Unknown"
        holder.tvAddress.text = item.address
        holder.tvRssi.text = "${item.rssi} dBm"
        holder.tvStatus.text = if (item.isConnected) "Terhubung" else "Tidak terhubung"
        holder.tvStatus.setTextColor(if (item.isConnected) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newItems: List<DeviceItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}