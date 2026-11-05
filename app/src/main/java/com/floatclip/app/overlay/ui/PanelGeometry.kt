package com.floatclip.app.overlay.ui

/** Window coordinates share the same TOP|START space for both overlay surfaces. */
object PanelGeometry {
    data class Box(val x:Int,val y:Int,val width:Int,val height:Int)
    data class Pose(val x:Float,val y:Float,val scaleX:Float,val scaleY:Float)
    const val LEFT=1
    const val RIGHT=2
    const val TOP=4
    const val BOTTOM=8
    fun resizeEdges(x:Float,y:Float,width:Int,height:Int,edge:Int):Int {
        var result=0
        if(x<=edge)result=result or LEFT else if(x>=width-edge)result=result or RIGHT
        if(y<=edge)result=result or TOP else if(y>=height-edge)result=result or BOTTOM
        return result
    }
    fun resize(start:Box,edges:Int,dx:Int,dy:Int,screenWidth:Int,screenHeight:Int,minWidth:Int,minHeight:Int,maxWidth:Int,maxHeight:Int,marginX:Int,marginY:Int):Box {
        val right=start.x+start.width
        val bottom=start.y+start.height
        val availableWidth=(screenWidth-marginX*2).coerceAtLeast(1)
        val availableHeight=(screenHeight-marginY*2).coerceAtLeast(1)
        val lowW=minWidth.coerceAtMost(availableWidth)
        val lowH=minHeight.coerceAtMost(availableHeight)
        val highW=maxWidth.coerceIn(lowW,availableWidth)
        val highH=maxHeight.coerceIn(lowH,availableHeight)
        val width=(when {
            edges and LEFT!=0 -> start.width-dx
            edges and RIGHT!=0 -> start.width+dx
            else -> start.width
        }).coerceIn(lowW,highW)
        val height=(when {
            edges and TOP!=0 -> start.height-dy
            edges and BOTTOM!=0 -> start.height+dy
            else -> start.height
        }).coerceIn(lowH,highH)
        val x=if(edges and LEFT!=0)right-width else start.x
        val y=if(edges and TOP!=0)bottom-height else start.y
        return Box(x.coerceIn(marginX,(screenWidth-width-marginX).coerceAtLeast(marginX)),
            y.coerceIn(marginY,(screenHeight-height-marginY).coerceAtLeast(marginY)),width,height)
    }
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
