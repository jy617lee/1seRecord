package com.simplemobiletools.camera.activities

import android.os.Bundle
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.RecyclerView
import com.simplemobiletools.camera.R
import com.simplemobiletools.camera.extensions.config
import java.io.File
import java.util.*


/**
 * Created by jeeyunlee on 04/11/2017.
 */
class GalleryActivity : SimpleActivity(){
    var arrayVideoTitles = ArrayList<String>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        val recyclerView = findViewById(R.id.image_gallery) as RecyclerView
        recyclerView.setHasFixedSize(true)

        val layoutManager = GridLayoutManager(applicationContext, 2)
        recyclerView.layoutManager = layoutManager

        //배열에 비디오 제목 넣기
        arrayVideoTitles = getVideoTitles()
        //갤러리 뷰로 만들어서 뿌리기 (제목을 파싱해서 날짜로 뿌리기)
        //썸네일 클릭하면 큰 화면으로 나오기


       val createLists = prepareData(arrayVideoTitles)
        val adapter = VideoThumbnailAdapter(applicationContext, createLists)
        recyclerView.adapter = adapter
    }

    fun getVideoTitles() : ArrayList<String>{
        var dirRootPath = File(config.savePhotosFolder)
        var cntFiles : Int = 0
        cntFiles = dirRootPath.listFiles().size
        var arrayVideoTitles = ArrayList<String>(cntFiles)
        for(video in dirRootPath.listFiles()){
            if(video.length() != 0L){
                arrayVideoTitles.add(video.absolutePath)
            }
        }
        return arrayVideoTitles
    }

    private fun prepareData(videoList : ArrayList<String>): ArrayList<CreateList> {
        val theimage = ArrayList<CreateList>()
        for (i in 0 until videoList.size) {
            val createList = CreateList()
            createList.title = videoList[i]
            createList.video = videoList[i]
            theimage.add(createList)
        }
        return theimage
    }
}


