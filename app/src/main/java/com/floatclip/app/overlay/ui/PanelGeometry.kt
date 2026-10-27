package com.floatclip.app.overlay.ui

/** Window coordinates share the same TOP|START space for both overlay surfaces. */
object PanelGeometry {
    data class Box(val x:Int,val y:Int,val width:Int,val height:Int)
    data class Pose(val x:Float,val y:Float,val scaleX:Float,val scaleY:Float)
    fun atBubble(panel:Box,bubble:Box)=Pose(
        bubble.x+bubble.width/2f-panel.width/2f,
        bubble.y+bubble.height/2f-panel.height/2f,
        bubble.width.toFloat()/panel.width.coerceAtLeast(1),
        bubble.height.toFloat()/panel.height.coerceAtLeast(1),
    )
    fun placement(screenWidth:Int,screenHeight:Int,panel:Box,bubble:Box,follow:Boolean,marginX:Int,marginY:Int):Box {
        val maxX=(screenWidth-panel.width-marginX).coerceAtLeast(marginX)
        val maxY=(screenHeight-panel.height-marginY).coerceAtLeast(marginY)
        val x=if(follow) {
            if(bubble.x+bubble.width/2>=screenWidth/2)screenWidth-panel.width-marginX else marginX
        } else panel.x
        val y=if(follow)bubble.y+bubble.height/2-panel.height/2 else panel.y
        return panel.copy(x=x.coerceIn(marginX,maxX),y=y.coerceIn(marginY,maxY))
    }
}
