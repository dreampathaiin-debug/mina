@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.meenai.mina.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.meenai.mina.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Inter Tight:几何无衬线,可变字重。 */
private fun itFont(weight: FontWeight) = Font(
    R.font.inter_tight_var,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val InterTight = FontFamily(
    itFont(FontWeight.Normal),
    itFont(FontWeight.Medium),
    itFont(FontWeight.SemiBold),
    itFont(FontWeight.Bold),
)

/** 字号与字重。只定大小、字重、行高,颜色由用的地方给。 */
object MinaType {
    /** 大标题:40sp SemiBold。空状态、欢迎页、Home 问候。 */
    val LargeTitle = TextStyle(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold, fontFamily = InterTight)
    /** 页面标题:顶栏。 */
    val Title = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = InterTight)
    /** 正文、行标题。 */
    val Body = TextStyle(fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal, fontFamily = InterTight)
    /** 卡片标题等需要强调的正文。 */
    val BodyStrong = TextStyle(fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = InterTight)
    /** 设置行的标题:18sp Medium。 */
    val RowTitle = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, fontFamily = InterTight)
    /** 说明文字。 */
    val Caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal, fontFamily = InterTight)
    /** 分组小标题。 */
    val Label = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, fontFamily = InterTight)
    /** 提示、页脚。 */
    val Hint = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal, fontFamily = InterTight)
    /** 行尾的值。 */
    val Value = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, fontFamily = InterTight)
}

/** 间距。页面左右留 [Page]。 */
object MinaSpace {
    val S4 = 4.dp
    val S8 = 8.dp
    val S12 = 12.dp
    val S16 = 16.dp
    val S20 = 20.dp
    val S24 = 24.dp
    val Page = 16.dp
}

/** 各类元素的尺寸。 */
object MinaSize {
    val TopBar = 64.dp
    val IconButton = 56.dp
    val RowMin = 72.dp
    val Button = 52.dp
    val Composer = 52.dp
    val Icon = 22.dp
    /** 列表行左边的图标块,里面的图形是 [TileIcon]。 */
    val Tile = 30.dp
    val TileIcon = 24.dp
    /** 行尾箭头等小图标。 */
    val SmallIcon = 16.dp
    const val IconStroke = 1.7f
}

/** 圆角。 */
object MinaRadius {
    val Card = 28.dp
    val Bubble = 20.dp
    val BubbleTail = 6.dp
    val Button = 14.dp
    val Tile = 12.dp
}

/** 常用形状;顶层常量,重组时不新建。 */
object MinaShapes {
    val Card = RoundedCornerShape(MinaRadius.Card)
    /** 用户消息气泡:右下角是尾角。 */
    val UserBubble = RoundedCornerShape(MinaRadius.Bubble, MinaRadius.Bubble, MinaRadius.BubbleTail, MinaRadius.Bubble)
    /** Mina 的回答:左下角是尾角,和用户气泡对称。 */
    val AnswerBubble = RoundedCornerShape(MinaRadius.Bubble, MinaRadius.Bubble, MinaRadius.Bubble, MinaRadius.BubbleTail)
    val Button = RoundedCornerShape(percent = 50)
    val Tile = RoundedCornerShape(MinaRadius.Tile)
    val Pill = CircleShape
}
