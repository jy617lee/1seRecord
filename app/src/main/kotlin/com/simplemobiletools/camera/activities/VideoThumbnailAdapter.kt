package com.simplemobiletools.camera.activities

import android.content.Context
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.simplemobiletools.camera.R




/**
 * Created by jeeyunlee on 04/11/2017.
 */
class VideoThumbnailAdapter(private val context: Context,
                            private val galleryList: ArrayList<CreateList>)
                                : RecyclerView.Adapter<VideoThumbnailAdapter.ViewHolder>() {

    var cols : Int = 2
    constructor(context : Context, galleryList: ArrayList<CreateList>, cols : Int)
    : this(context, galleryList) {
        this.cols = cols
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, i: Int): VideoThumbnailAdapter.ViewHolder {
        val view = LayoutInflater.from(viewGroup.context).inflate(R.layout.compo_one_video, viewGroup, false)

        val width : Int = viewGroup.measuredWidth / cols
        view.layoutParams = RecyclerView.LayoutParams(width, width)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: VideoThumbnailAdapter.ViewHolder, i: Int) {
        //뷰 크기 지정해주기
        //제목 파싱해주기
        var title : String? = parseTitle(galleryList[i].title)
        if(title != null){
            viewHolder.title.setText(title)
        }

        //썸네일 이미지 넣기
        viewHolder.img.setScaleType(ImageView.ScaleType.CENTER_CROP)
        val thumbnail = ThumbnailUtils.createVideoThumbnail(galleryList[i].title, MediaStore.Video.Thumbnails.MINI_KIND)
        viewHolder.img.setImageBitmap(thumbnail)
    }


    private fun parseTitle(titleInput : String) : String? {
        val regex = Regex(pattern = "20[0-9]{6}")

        val matchedResults = regex.findAll(input = titleInput, startIndex = 0)
        if(matchedResults == null){
            return null
        }else{
            val result = StringBuilder()
            for (matchedText in matchedResults) {
                result.append(matchedText.value + " ")
            }
            return result.toString()
        }
    }

    override fun getItemCount(): Int {
        return galleryList.size
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView
        val img: ImageView
        //타이틀 / 썸네일 넣어주기
        init {
            title = view.findViewById(R.id.title) as TextView
            img = view.findViewById(R.id.video_thumbnail) as ImageView
        }
    }
}