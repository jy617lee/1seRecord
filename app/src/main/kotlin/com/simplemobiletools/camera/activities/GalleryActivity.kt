package com.simplemobiletools.camera.activities

import android.os.Bundle
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.RecyclerView
import com.simplemobiletools.camera.R
import com.simplemobiletools.camera.dialogs.VideoConfigDialog
import com.simplemobiletools.camera.extensions.config
import kotlinx.android.synthetic.main.activity_gallery.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Created by jeeyunlee on 04/11/2017.
 */
class GalleryActivity : SimpleActivity(){
    var arrayVideoTitles = ArrayList<String>()
    var cols = 3;
    var startDate : String? = "0"
    var endDate : String? = "0"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        val recyclerView = findViewById(R.id.image_gallery) as RecyclerView
        recyclerView.setHasFixedSize(true)

        val layoutManager = GridLayoutManager(applicationContext, cols)
        recyclerView.layoutManager = layoutManager

        //배열에 비디오 제목 넣기
        arrayVideoTitles = getVideoTitles()
        //갤러리 뷰로 만들어서 뿌리기 (제목을 파싱해서 날짜로 뿌리기)
        //썸네일 클릭하면 큰 화면으로 나오기
        if(arrayVideoTitles.size > 0) {
            startDate = parseTitle(arrayVideoTitles[0])
            endDate = parseTitle(arrayVideoTitles[arrayVideoTitles.size-1])
        }else{
            var cal = Calendar.getInstance()
            val myFormat = "MM/dd/yyyy" // mention the format you need
            val sdf = SimpleDateFormat(myFormat, Locale.US)

            startDate = sdf.format(cal.time)
            endDate = startDate
        }

        val createLists = prepareData(arrayVideoTitles)
        val adapter = VideoThumbnailAdapter(applicationContext, createLists, cols)
        recyclerView.adapter = adapter
        btn_try_make_movie.setOnClickListener{showMakeMovieDialog()}
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

    private fun showMakeMovieDialog(){
        //다이얼로그 띄워주고
            //시작 텍스트 가져오기
            //끝 텍스트 가져오기
        var startDate = Calendar.getInstance()
        startDate.set(Calendar.YEAR, Integer.parseInt(this.startDate?.substring(0, 4)))
        startDate.set(Calendar.MONTH, Integer.parseInt(this.startDate?.substring(4, 6))-1)
        startDate.set(Calendar.DAY_OF_MONTH, Integer.parseInt(this.startDate?.substring(6, 8)))

        var endDate = Calendar.getInstance()
        endDate.set(Calendar.YEAR, Integer.parseInt(this.endDate?.substring(0, 4)))
        endDate.set(Calendar.MONTH, Integer.parseInt(this.endDate?.substring(4, 6))-1)
        endDate.set(Calendar.DAY_OF_MONTH, Integer.parseInt(this.endDate?.substring(6, 8)))
        VideoConfigDialog(this, startDate, endDate, config){
            //정해진 날짜에 대해서 합치기 시작!
        }
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


