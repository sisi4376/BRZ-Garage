package android.app

class AlertDialog(val labels:Array<String>,val select:(Any?,Int)->Unit){
    companion object{var last:AlertDialog?=null}
    fun pick(index:Int){select(null,index)}
    fun cancel(){}
    class Builder(context:Any){
        private var labels=emptyArray<String>();private var selected:(Any?,Int)->Unit={_,_->}
        fun setTitle(value:String)=this
        fun setItems(values:Array<String>,listener:(Any?,Int)->Unit):Builder{labels=values;selected=listener;return this}
        fun setNegativeButton(label:String,listener:Any?)=this
        fun show():AlertDialog=AlertDialog(labels,selected).also{last=it}
    }
}
