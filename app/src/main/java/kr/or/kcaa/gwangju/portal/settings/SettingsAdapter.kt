package kr.or.kcaa.gwangju.portal.settings

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import kr.or.kcaa.gwangju.portal.R
import kr.or.kcaa.gwangju.portal.settings.SettingsItem.Kind
import kr.or.kcaa.gwangju.portal.settings.SettingsItem.Tone

/** [SettingsItem] 목록을 그리는 어댑터. 항목 종류가 늘어도 여기서만 뷰 타입을 더하면 됩니다. */
class SettingsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<SettingsItem> = emptyList()

    fun submit(newItems: List<SettingsItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is SettingsItem.Header -> TYPE_HEADER
        is SettingsItem.Note -> TYPE_NOTE
        is SettingsItem.Row -> TYPE_ROW
        is SettingsItem.Hero -> TYPE_HERO
        is SettingsItem.Tiles -> TYPE_TILES
        is SettingsItem.ActionButton -> TYPE_BUTTON
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> TextHolder(inflater.inflate(R.layout.item_setting_header, parent, false) as TextView)
            TYPE_NOTE -> TextHolder(inflater.inflate(R.layout.item_setting_note, parent, false) as TextView)
            TYPE_HERO -> HeroHolder(inflater.inflate(R.layout.item_setting_hero, parent, false))
            TYPE_TILES -> TilesHolder(inflater.inflate(R.layout.item_setting_tiles, parent, false))
            TYPE_BUTTON -> ButtonHolder(inflater.inflate(R.layout.item_setting_button, parent, false))
            else -> RowHolder(inflater.inflate(R.layout.item_setting_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SettingsItem.Header -> (holder as TextHolder).text.text = item.title
            is SettingsItem.Note -> (holder as TextHolder).text.text = item.text
            is SettingsItem.Row -> (holder as RowHolder).bind(item, isLastInGroup(position))
            is SettingsItem.Hero -> (holder as HeroHolder).bind(item)
            is SettingsItem.Tiles -> (holder as TilesHolder).bind(item)
            is SettingsItem.ActionButton -> (holder as ButtonHolder).bind(item)
        }
    }

    /** 다음 항목이 행이 아니면(구역 끝) 구분선을 숨깁니다. */
    private fun isLastInGroup(position: Int): Boolean = items.getOrNull(position + 1) !is SettingsItem.Row

    private class TextHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    /** 상태 알약의 배경·글자색을 상태에 맞게 맞춥니다. (성공/주의/기본) */
    private fun styleChip(chip: TextView, tone: Tone) {
        val context = chip.context
        val (background, text) = when (tone) {
            Tone.GOOD -> R.color.portal_success_soft to R.color.text_success
            Tone.WARN -> R.color.portal_warn_soft to R.color.text_warn
            Tone.NEUTRAL -> R.color.portal_accent_soft to R.color.portal_accent
        }
        chip.background.mutate().setTint(ContextCompat.getColor(context, background))
        chip.setTextColor(ContextCompat.getColor(context, text))
    }

    private inner class HeroHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon = view.findViewById<ImageView>(R.id.heroIcon)
        private val eyebrow = view.findViewById<TextView>(R.id.heroEyebrow)
        private val title = view.findViewById<TextView>(R.id.heroTitle)
        private val chip = view.findViewById<TextView>(R.id.heroChip)
        private val description = view.findViewById<TextView>(R.id.heroDescription)
        private val primary = view.findViewById<MaterialButton>(R.id.heroPrimary)

        fun bind(hero: SettingsItem.Hero) {
            icon.setImageResource(hero.iconRes)
            eyebrow.text = hero.eyebrow
            title.text = hero.title
            description.text = hero.description
            chip.visibility = if (hero.chip.isNullOrBlank()) View.GONE else View.VISIBLE
            chip.text = hero.chip
            styleChip(chip, hero.chipTone)
            primary.text = hero.primaryLabel
            primary.setOnClickListener { hero.onPrimary() }
        }
    }

    private class TilesHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val left = view.findViewById<MaterialCardView>(R.id.tileLeft)
        private val right = view.findViewById<MaterialCardView>(R.id.tileRight)

        fun bind(tiles: SettingsItem.Tiles) {
            bindTile(left, tiles.left)
            val rightTile = tiles.right
            if (rightTile == null) {
                // 홀수 장이면 오른쪽 자리는 비워 두어 왼쪽 타일의 폭을 일정하게 유지
                right.visibility = View.INVISIBLE
                right.setOnClickListener(null)
                right.isClickable = false
            } else {
                right.visibility = View.VISIBLE
                bindTile(right, rightTile)
            }
        }

        private fun bindTile(card: MaterialCardView, tile: SettingsItem.Tile) {
            card.findViewById<ImageView>(R.id.tileIcon).setImageResource(tile.iconRes)
            card.findViewById<TextView>(R.id.tileTitle).text = tile.title
            card.findViewById<TextView>(R.id.tileDescription).apply {
                text = tile.description
                visibility = if (tile.description.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            card.isClickable = true
            card.setOnClickListener { tile.onClick() }
        }
    }

    private class ButtonHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val button = view.findViewById<MaterialButton>(R.id.actionButton)

        fun bind(item: SettingsItem.ActionButton) {
            val context = itemView.context
            button.text = item.label
            button.setIconResource(item.iconRes)
            val background = if (item.danger) R.color.portal_danger_soft else R.color.portal_surface
            val foreground = if (item.danger) R.color.text_danger else R.color.text_body
            button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, background))
            button.setTextColor(ContextCompat.getColor(context, foreground))
            button.iconTint = ColorStateList.valueOf(ContextCompat.getColor(context, foreground))
            button.strokeWidth = if (item.danger) 0 else (1 * context.resources.displayMetrics.density).toInt()
            button.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.portal_button_border))
            button.setOnClickListener { item.onClick() }
        }
    }

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val content = view.findViewById<View>(R.id.rowContent)
        private val title = view.findViewById<TextView>(R.id.tvTitle)
        private val description = view.findViewById<TextView>(R.id.tvDescription)
        private val value = view.findViewById<TextView>(R.id.tvValue)
        private val trailing = view.findViewById<TextView>(R.id.tvTrailing)
        private val toggle = view.findViewById<MaterialSwitch>(R.id.switchView)
        private val divider = view.findViewById<View>(R.id.divider)

        fun bind(row: SettingsItem.Row, lastInGroup: Boolean) {
            val context = itemView.context
            title.text = row.title
            title.setTextColor(
                ContextCompat.getColor(context, if (row.danger) R.color.text_danger else R.color.text_primary)
            )
            description.text = row.description
            description.visibility = if (row.description.isNullOrBlank()) View.GONE else View.VISIBLE

            value.text = row.value
            value.visibility = if (row.value.isNullOrBlank()) View.GONE else View.VISIBLE
            value.setTextColor(
                ContextCompat.getColor(
                    context,
                    when (row.tone) {
                        Tone.GOOD -> R.color.text_success
                        Tone.WARN -> R.color.text_warn
                        Tone.NEUTRAL -> R.color.text_secondary
                    }
                )
            )

            val trailingText = when (row.kind) {
                Kind.NAV, Kind.ACTION -> "›"
                Kind.LINK -> "↗"
                else -> null
            }
            trailing.text = trailingText
            trailing.visibility = if (trailingText == null) View.GONE else View.VISIBLE

            toggle.visibility = if (row.kind == Kind.SWITCH) View.VISIBLE else View.GONE
            toggle.isChecked = row.checked
            toggle.isEnabled = row.enabled

            val clickable = row.kind != Kind.INFO && row.enabled
            content.isEnabled = clickable
            content.isClickable = clickable
            content.alpha = if (row.enabled) 1f else 0.5f
            content.setOnClickListener(
                if (!clickable) null else View.OnClickListener {
                    if (row.kind == Kind.SWITCH) row.onToggle?.invoke(!row.checked) else row.onClick?.invoke()
                }
            )
            if (row.kind == Kind.SWITCH) {
                // 스위치는 접근성 서비스에서 켜짐/꺼짐 상태가 한 번에 읽히도록 행 전체를 스위치로 노출
                content.contentDescription = null
            }

            divider.visibility = if (lastInGroup) View.GONE else View.VISIBLE
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_NOTE = 1
        const val TYPE_ROW = 2
        const val TYPE_HERO = 3
        const val TYPE_TILES = 4
        const val TYPE_BUTTON = 5
    }
}
