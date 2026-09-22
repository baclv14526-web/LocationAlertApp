package com.locationalert

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.locationalert.databinding.ItemRouteStepBinding

/**
 * Adapter cho RecyclerView danh sách bước đường đi, hiển thị dạng
 * timeline dọc: vị trí hiện tại (trên cùng) → đích đến (dưới cùng),
 * mỗi bước là 1 mốc trên timeline với đường nối liên tục xuyên suốt —
 * trực quan giống chỉ đường Google Maps.
 */
class RouteStepAdapter(
    private val steps: List<RouteHelper.RouteStep>,
    private val extractModifier: (String) -> String,
    private val colorAccentGreen: Int,
    private val colorTextPrimary: Int,
    private val colorSurfaceCard: Int,
    private val colorBgDark: Int
) : RecyclerView.Adapter<RouteStepAdapter.StepViewHolder>() {

    inner class StepViewHolder(val binding: ItemRouteStepBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
        val binding = ItemRouteStepBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return StepViewHolder(binding)
    }

    override fun getItemCount(): Int = steps.size

    override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
        val step = steps[position]
        val b = holder.binding
        val isFirst = position == 0
        val isLast  = position == steps.lastIndex

        // Số thứ tự bước — bỏ qua cho điểm xuất phát/đến nơi vì đã có nhãn riêng
        b.tvStepNumber.text = when {
            step.maneuver == "depart" -> "Điểm bắt đầu"
            step.maneuver == "arrive" -> "Điểm kết thúc"
            else -> "Bước ${position + 1}/${steps.size}"
        }

        // Icon + màu nền theo loại bước — chấm to hơn ở 2 đầu timeline (xuất
        // phát/đến nơi) để nổi bật, giống quy ước của Google Maps
        b.tvStepIcon.text = RouteHelper.maneuverIcon(step.maneuver, extractModifier(step.instruction))
        val iconSizeDp = if (step.maneuver == "depart" || step.maneuver == "arrive") 34 else 26
        val density = b.tvStepIcon.resources.displayMetrics.density
        b.tvStepIcon.layoutParams = b.tvStepIcon.layoutParams.apply {
            width  = (iconSizeDp * density).toInt()
            height = (iconSizeDp * density).toInt()
        }
        when (step.maneuver) {
            "depart" -> b.tvStepIcon.setBackgroundResource(R.drawable.bg_step_icon_depart)
            "arrive" -> b.tvStepIcon.setBackgroundResource(R.drawable.bg_step_icon_arrive)
            else     -> b.tvStepIcon.setBackgroundResource(R.drawable.bg_step_icon)
        }

        // Tên đường: ưu tiên tên có nghĩa
        b.tvStepStreet.text = when {
            step.streetName.isNotEmpty() -> step.streetName
            step.maneuver == "depart"    -> "Vị trí hiện tại của bạn"
            step.maneuver == "arrive"    -> "Điểm đến"
            else                         -> "(Không có tên đường)"
        }
        b.tvStepStreet.setTextColor(if (isLast) colorAccentGreen else colorTextPrimary)

        b.tvStepInstruction.text = step.instruction

        // Khoảng cách
        if (step.distanceM > 0) {
            b.tvStepDistance.text       = RouteHelper.formatDistance(step.distanceM)
            b.tvStepDistance.visibility = View.VISIBLE
        } else {
            b.tvStepDistance.visibility = View.GONE
        }

        // Timeline liên tục: ẩn đoạn nối phía trên ở bước đầu tiên (không có
        // gì phía trên vị trí hiện tại) và đoạn nối phía dưới ở bước cuối
        // (không có gì sau điểm đến) — đoạn giữa luôn hiện để timeline
        // liền mạch từ trên xuống dưới.
        b.viewLineTop.visibility    = if (isFirst) View.INVISIBLE else View.VISIBLE
        b.viewLineBottom.visibility = if (isLast)  View.INVISIBLE else View.VISIBLE

        // Nền xen kẽ nhẹ cho dễ theo dõi từng bước, đích đến làm nổi bật riêng
        b.root.setBackgroundColor(
            when {
                isLast -> colorSurfaceCard
                position % 2 == 0 -> colorSurfaceCard
                else -> colorBgDark
            }
        )
    }
}
