package dev.digitalducktape.openrun

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

internal class TrailGeometry(val route:TrailPreview) {
    val distances=DoubleArray(route.points.size)
    init { for(i in 1 until distances.size) distances[i]=distances[i-1]+TrailPreview(route.points.subList(i-1,i+1)).distanceMeters }
    val length get()=distances.last()
    fun position(distance:Double):Pair<Int,TrailPoint> {
        val d=distance.takeIf { it.isFinite() }?.coerceIn(0.0,length) ?: 0.0
        val found=distances.binarySearch(d)
        if(found>=0) return found to route.points[found]
        val i=(-found-1).coerceIn(1,distances.lastIndex)
        val a=route.points[i-1];val b=route.points[i]
        val f=(d-distances[i-1])/(distances[i]-distances[i-1]).coerceAtLeast(0.000001)
        return (i-1) to TrailPoint(a.latitude+(b.latitude-a.latitude)*f,a.longitude+(b.longitude-a.longitude)*f,if(a.elevation!=null && b.elevation!=null) a.elevation+(b.elevation-a.elevation)*f else null)
    }
}

/** Offline GPX outline, with position interpolated from recorded treadmill distance. */
@Composable internal fun HikeMap(hike:SavedHike,distance:Double,paused:Boolean=false,warming:Boolean=false,compact:Boolean=false) {
    val geometry=remember(hike.id) { TrailGeometry(hike.route) }
    val traveled=distance.coerceIn(0.0,geometry.length)
    val (index,position)=geometry.position(traveled)
    val green=Color(0xFFB7EF79)
    Column(Modifier.fillMaxWidth().background(Color(0xFF101713),RoundedCornerShape(18.dp)).padding(if(compact) 12.dp else 20.dp),verticalArrangement=Arrangement.spacedBy(if(compact) 6.dp else 10.dp)) {
        Text(hike.name,fontSize=if(compact) 21.sp else 25.sp,fontWeight=FontWeight.Bold)
        Text("${if(warming) "WARM-UP" else if(paused) "PAUSED" else "TRAIL PROGRESS"} · %.2f / %.2f mi · %.2f mi remaining".format(traveled/1609.344,geometry.length/1609.344,(geometry.length-traveled)/1609.344),color=green)
        Canvas(Modifier.fillMaxWidth().height(if(compact) 150.dp else 220.dp).semantics { contentDescription="${hike.name} trail map. ${(traveled/geometry.length*100).toInt()} percent complete. White marker is your position." }) {
            val pts=hike.route.points
            val minX=pts.minOf { it.longitude };val maxX=pts.maxOf { it.longitude }
            val minY=pts.minOf { it.latitude };val maxY=pts.maxOf { it.latitude }
            val cosLat=cos(Math.toRadians((minY+maxY)/2)).coerceAtLeast(0.01)
            val w=(maxX-minX)*cosLat;val h=maxY-minY
            val inset=20.dp.toPx()
            val scale=minOf((size.width-2*inset)/w.coerceAtLeast(0.000001),(size.height-2*inset)/h.coerceAtLeast(0.000001))
            fun xy(p:TrailPoint)=Offset(((size.width-w*scale)/2+(p.longitude-minX)*cosLat*scale).toFloat(),((size.height-h*scale)/2+(maxY-p.latitude)*scale).toFloat())
            val full=Path();pts.forEachIndexed { i,p -> val q=xy(p);if(i==0) full.moveTo(q.x,q.y) else full.lineTo(q.x,q.y) }
            drawPath(full,Color(0xFF53635A),style=Stroke(5.dp.toPx()))
            val covered=Path();for(i in 0..index) { val q=xy(pts[i]);if(i==0) covered.moveTo(q.x,q.y) else covered.lineTo(q.x,q.y) }
            val marker=xy(position);covered.lineTo(marker.x,marker.y)
            drawPath(covered,green,style=Stroke(5.dp.toPx()))
            drawCircle(green,5.dp.toPx(),xy(pts.first()))
            drawCircle(Color(0xFFDFAD65),5.dp.toPx(),xy(pts.last()))
            drawCircle(Color(0xFF101713),11.dp.toPx(),marker)
            drawCircle(Color.White,7.dp.toPx(),marker)
        }
        if(!compact) Text("White: you · Green: completed · Gray: remaining · Route outline from GPX",color=Color(0xFF9EAEA1),fontSize=13.sp)
        if(hike.route.hasElevation) {
            val points=hike.route.points
            val low=remember(hike.id) { points.minOf { it.elevation!! } }
            val high=remember(hike.id) { points.maxOf { it.elevation!! } }
            val gain=remember(hike.id) { points.zipWithNext().sumOf { (a,b)->(b.elevation!!-a.elevation!!).coerceAtLeast(0.0) } }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("ELEVATION PROFILE",color=green,fontSize=13.sp)
                Text("GPX gain ≈ %.0f ft · elevation %.0f–%.0f ft".format(gain*3.28084,low*3.28084,high*3.28084),color=Color(0xFF9EAEA1),fontSize=13.sp)
            }
            Canvas(Modifier.fillMaxWidth().height(if(compact) 85.dp else 130.dp).semantics {
                contentDescription="Elevation profile by trail distance. Current route elevation %.0f feet. Estimated total GPX gain %.0f feet.".format((position.elevation ?: low)*3.28084,gain*3.28084)
            }) {
                val pad=10.dp.toPx()
                val baseline=size.height-pad
                fun xy(d:Double,e:Double)=Offset((pad+(size.width-2*pad)*d/geometry.length).toFloat(),(baseline-(e-low)/(high-low).coerceAtLeast(1.0)*(size.height-2*pad)).toFloat())
                val line=Path()
                points.forEachIndexed { i,p -> val q=xy(geometry.distances[i],p.elevation!!);if(i==0) line.moveTo(q.x,q.y) else line.lineTo(q.x,q.y) }
                val fill=Path().apply { addPath(line);lineTo(size.width-pad,baseline);lineTo(pad,baseline);close() }
                drawPath(fill,green.copy(alpha=0.08f))
                drawPath(line,Color(0xFF687A6D),style=Stroke(2.dp.toPx()))
                val marker=xy(traveled,position.elevation ?: low)
                clipRect(right=marker.x) {
                    drawPath(fill,green.copy(alpha=0.15f))
                    drawPath(line,green,style=Stroke(3.dp.toPx()))
                }
                drawLine(Color.White.copy(alpha=0.5f),Offset(marker.x,pad),Offset(marker.x,baseline),1.dp.toPx())
                drawCircle(Color(0xFF101713),7.dp.toPx(),marker)
                drawCircle(Color.White,4.dp.toPx(),marker)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("0 mi",fontSize=12.sp,color=Color(0xFF9EAEA1))
                Text("%.2f mi".format(geometry.length/1609.344/2),fontSize=12.sp,color=Color(0xFF9EAEA1))
                Text("%.2f mi".format(geometry.length/1609.344),fontSize=12.sp,color=Color(0xFF9EAEA1))
            }
        }
    }
}
