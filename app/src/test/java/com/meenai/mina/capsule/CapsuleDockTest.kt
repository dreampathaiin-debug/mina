package com.meenai.mina.capsule

import com.meenai.mina.cap.phone.a11y.OverlayArea
import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleDockTest {
    // 1000×2000 screen,status bar 80,Bottom gesture bar 40;Visible capsule off the edge 30,away from the upper and lower 20,shadow edge 10
    private val area = DockArea.of(OverlayArea(1000, 2000, insetTop = 80, insetBottom = 40), margin = 30, gap = 20, room = 10)

    @Test fun areaAvoidsBarsAndKeepsMargins() {
        assertEquals(DockArea(1000, left = 20, top = 90, right = 980, bottom = 1950, edgeLeft = -10, edgeRight = 1010), area)
    }

    @Test fun fractionAndYRoundTrip() {
        assertEquals(90, CapsuleDocking.y(0f, area, 100))
        assertEquals(1850, CapsuleDocking.y(1f, area, 100))
        assertEquals(970, CapsuleDocking.y(0.5f, area, 100))
        assertEquals(0.5f, CapsuleDocking.fraction(970, area, 100), 0.001f)
        //Clamping out of range
        assertEquals(0f, CapsuleDocking.fraction(-500, area, 100))
        assertEquals(1f, CapsuleDocking.fraction(5000, area, 100))
        assertEquals(90, CapsuleDocking.y(-3f, area, 100))
    }

    @Test fun sameFractionAfterRotation() {
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        val f = CapsuleDocking.fraction(CapsuleDocking.y(0.25f, area, 100), area, 100)
        val y = CapsuleDocking.y(f, landscape, 100)
        //Horizontal screen:Up 50, Down 990,One quarter of range 840
        assertEquals(50 + 210, y)
        //Bangs on the left:Get out of the way when parked on the left
        assertEquals(100, CapsuleDocking.dockedX(DockSide.Left, landscape, 200))
        assertEquals(1780, CapsuleDocking.dockedX(DockSide.Right, landscape, 200))
    }

    @Test fun tallerWindowMovesUpToFit() {
        //Card 600 high,It cannot be lowered from the height of the capsule
        assertEquals(1950 - 600, CapsuleDocking.fitY(CapsuleDocking.y(1f, area, 100), area, 600))
        assertEquals(500, CapsuleDocking.fitY(500, area, 600))
        //higher than the entire range:Stick to the top
        assertEquals(90, CapsuleDocking.fitY(500, area, 5000))
    }

    @Test fun dragStopsAtTheScreenEdge() {
        assertEquals(-10, CapsuleDocking.dragX(-300, area, 200))
        assertEquals(1010 - 200, CapsuleDocking.dragX(5000, area, 200))
        assertEquals(400, CapsuleDocking.dragX(400, area, 200))
    }

    @Test fun releaseSnapsToTheNearestSide() {
        assertEquals(CapsuleDock(DockSide.Left, 0f), CapsuleDocking.release(250, 90, 200, 100, area))
        assertEquals(DockSide.Right, CapsuleDocking.release(450, 500, 200, 100, area).side)
        assertEquals(DockSide.Left, CapsuleDocking.release(380, 500, 200, 100, area).side)
        assertEquals(20, CapsuleDocking.dockedX(DockSide.Left, area, 200))
        assertEquals(780, CapsuleDocking.dockedX(DockSide.Right, area, 200))
        //When the bangs are on the left, the width of the two sides is different.:left 100,right 20
        val lopsided = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        assertEquals(100, CapsuleDocking.edgeOffset(DockSide.Left, lopsided))
        assertEquals(20, CapsuleDocking.edgeOffset(DockSide.Right, lopsided))
    }

    @Test fun keyboardPushesTheRangeUp() {
        //The input method is above 1200:The visible part separates it 20
        val up = area.above(1200, gap = 20, room = 10)
        assertEquals(1190, up.bottom)
        assertEquals(1190 - 600, CapsuleDocking.fitY(CapsuleDocking.y(1f, area, 100), up, 600))
        assertEquals(area, area.above(null, 20, 10))
        //The input method is higher than the swingable range:not over the top
        assertEquals(area.top, area.above(10, 20, 10).bottom)
    }

    // ----------card ----------

    /**highest card(Visible 1160 high)Together with the upper and lower shadow edges the window height. */
    private val full = 1160 + 20

    @Test fun cardCapIsAShareOfTheScreen() {
        //2000 high screen:Highest 58%,That's it if you can let it go(Dialogue cards are so high,The maximum number of task cards is this high)
        assertEquals(1160, CardDocking.maxHeight(2000, area, room = 10))
        //Input method pop-up(above 1200):receive it above,The middle section becomes shorter
        val up = area.above(1200, gap = 20, room = 10)
        assertEquals(1190 - 90 - 20, CardDocking.maxHeight(2000, up, room = 10))
        //Horizontal screen:Calculated according to the height of the horizontal screen
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        assertEquals(580, CardDocking.maxHeight(1000, landscape, room = 10))
    }

    @Test fun cardTopFollowsItsFraction() {
        //Above is 90..(1950 -highest card)in proportion to:The highest card in the middle, top and bottom
        assertEquals(90 + 340, CardDocking.y(0.5f, area, area, full, full))
        assertEquals(90, CardDocking.y(0f, area, area, full, full))
        assertEquals(1950 - full, CardDocking.y(1f, area, area, full, full))
        //Same ratio after screen rotation
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        assertEquals(50 + 170, CardDocking.y(0.5f, landscape, landscape, 600, 600))
    }

    @Test fun shorterTaskCardSharesTheTopWithTheChatCard() {
        //Task cards are only 500 high by content:The top is the same as the dialogue card of the same proportion.,Shorter down
        for (f in listOf(0f, 0.3f, 0.5f, 1f)) {
            assertEquals(CardDocking.y(f, area, area, full, full), CardDocking.y(f, area, area, full, 500))
        }
        //In the past, the fixed high card recording ratio was read as the above ratio.:Dialogue cards return to their original positions
        assertEquals(CapsuleDocking.y(0.7f, area, full), CardDocking.y(0.7f, area, area, full, full))
    }

    @Test fun switchingCardsKeepsTheTopAndShiftsUpOnlyWhenItOverflows() {
        val top = CardDocking.y(1f, area, area, full, 500)
        //Replace task cards with conversation cards:,The top doesn't move,grow downward
        assertEquals(top, CardDocking.y(1f, area, area, full, full))
        //Input method pop-up(above 1200):The short card won't move even if it's placed down,conversation cards(Received short 1100 +shadow edge)Move it up until you just put it down
        val up = area.above(1200, gap = 20, room = 10)
        val shortTop = CardDocking.y(0f, area, up, full, 500)
        assertEquals(90, shortTop)
        assertEquals(90 + 204, CardDocking.y(0.3f, area, up, full, 500))
        assertEquals(1190 - 700, CardDocking.y(1f, area, up, full, 700))
        assertEquals(90, CardDocking.y(0.5f, area, up, full, 1100))
    }

    @Test fun cardDragStaysOnScreenAndRemembersTheTopFraction() {
        assertEquals(90, CardDocking.dragY(430, -1000f, area, area, full, full))
        assertEquals(1950 - full, CardDocking.dragY(430, 2000f, area, area, full, full))
        assertEquals(530, CardDocking.dragY(430, 100f, area, area, full, full))
        //Drag short task cards all the way down:It stops at a place where conversation cards can also be placed.,The proportions noted down are within the range.
        assertEquals(1950 - full, CardDocking.dragY(430, 2000f, area, area, full, 500))
        assertEquals(1f, CardDocking.release(1950 - full, area, full))
        assertEquals(0.5f, CardDocking.release(430, area, full), 0.001f)
        //Press to disable the range of the input method.:The input method remains in the same place after being folded,It's the same if it's at another height.
        assertEquals(430, CardDocking.y(CardDocking.release(430, area, full), area, area, full, full))
        assertEquals(430, CardDocking.y(CardDocking.release(430, area, full), area, area, full, 500))
        //Drag when input method pops up:The whole picture does not appear above it
        val up = area.above(1200, gap = 20, room = 10)
        assertEquals(1190 - 500, CardDocking.dragY(430, 2000f, area, up, full, 500))
    }
}
