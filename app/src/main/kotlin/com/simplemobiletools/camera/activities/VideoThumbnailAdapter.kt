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

    override fun onCreateViewHolder(viewGroup: ViewGroup, i: Int): VideoThumbnailAdapter.ViewHolder {
        val view = LayoutInflater.from(viewGroup.context).inflate(R.layout.compo_one_video, viewGroup, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: VideoThumbnailAdapter.ViewHolder, i: Int) {
        //제목 파싱해주기
        val title =
        viewHolder.title.setText(galleryList[i].title)
        viewHolder.img.setScaleType(ImageView.ScaleType.CENTER_CROP)

        //uri에서 썸네일 구하기
        val thumbnail = ThumbnailUtils.createVideoThumbnail(galleryList[i].title, MediaStore.Video.Thumbnails.MICRO_KIND)
        viewHolder.img.setImageBitmap(thumbnail)
    }

    override fun getItemCount(): Int {
        return galleryList.size
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView
        val img: ImageView

        init {
            title = view.findViewById(R.id.title) as TextView
            img = view.findViewById(R.id.video_thumbnail) as ImageView
        }
    }
}