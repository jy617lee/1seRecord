package com.simplemobiletools.camera.dialogs

import android.app.DatePickerDialog
import android.graphics.Color
import android.support.v7.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.DatePicker
import android.widget.TextView
import android.widget.Toast
import com.googlecode.mp4parser.authoring.Movie
import com.googlecode.mp4parser.authoring.Track
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator
import com.googlecode.mp4parser.authoring.tracks.AppendTrack
import com.simplemobiletools.camera.Config
import com.simplemobiletools.camera.R
import com.simplemobiletools.camera.activities.SimpleActivity
import com.simplemobiletools.commons.extensions.setupDialogStuff
import kotlinx.android.synthetic.main.dialog_set_video_dialog.view.*
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*



/**
 * Created by jeeyunlee on 18/11/2017.
 */
class VideoConfigDialog(val activity: SimpleActivity, val startDate: Calendar, val endDate : Calendar, val config : Config, val callback: () -> Unit){
    var dialog: AlertDialog
    var curStartDate: String?
    var curEndDate: String?

    var mStartDate : Calendar = startDate
    var mEndDate : Calendar = endDate
    private val curStartDateView : TextView
    private val curEndDateView : TextView

    init {

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_set_video_dialog, null).apply {
            start_date_input.setOnClickListener{setSelectedDate(this, start_date_input, startDate)}
            end_date_input.setOnClickListener{setSelectedDate(this, end_date_input, endDate)}
        }

        curStartDate = calToString(mStartDate)
        curStartDateView = view.start_date_input
        curStartDateView.text = curStartDate

        curEndDate = calToString(mEndDate)
        curEndDateView = view.end_date_input
        curEndDateView.text = curEndDate
        dialog = AlertDialog.Builder(activity)
                .setPositiveButton("Make Movie", {
                    dialogInterface, i ->
                        makeDiary()
                })
                .create().apply{
            activity.setupDialogStuff(view, this)
        }
    }

    private fun setSelectedDate(view: View, textView: TextView, cal : Calendar){
        val dateSetListener = object : DatePickerDialog.OnDateSetListener {
            override fun onDateSet(view: DatePicker, year: Int, monthOfYear: Int,
                                   dayOfMonth: Int) {
                var tempStartDate = mStartDate
                var tempEndDate = mEndDate
                if(view == curStartDateView){
                    tempStartDate = cal
                }else{
                    tempEndDate = cal
                }

                if(tempEndDate < tempStartDate){
                    Toast.makeText(activity.applicationContext,
                            "end date cannot be earlier than start date",
                            Toast.LENGTH_SHORT).show()
                }else {
                    cal.set(Calendar.YEAR, year)
                    cal.set(Calendar.MONTH, monthOfYear)
                    cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    updateDateInView(cal, textView)
                }
            }
        }
        var datePickerDialog = DatePickerDialog(this.activity, dateSetListener,
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH))

        datePickerDialog.setOnShowListener {
            datePickerDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.BLACK)
        }
        datePickerDialog.show()
    }


    var cntFiles : Int = 0
    fun makeDiary(){
        //그 폴더의 uri모두 가져오기
        var dirRootPath = File(config.savePhotosFolder)
        cntFiles = dirRootPath.listFiles().size
        var videoUris = ArrayList<String>(cntFiles)
        for(video in dirRootPath.listFiles()){
            if(video.length() != 0L){
                videoUris.add(video.absolutePath)
            }
        }

        //리스트에 비디오 저장하기
        var videoTrack = LinkedList<Track>()
        var audioTrack = LinkedList<Track>()
        for(videoUri in videoUris){
            var movie = MovieCreator.build(videoUri)
            //날짜랑 제목 해서 커팅하기
            for(track in movie.tracks){
                if(track.handler.equals("soun")){
                    audioTrack.add(track)
                }else if(track.handler.equals("vide")) {
                    videoTrack.add(track)
                }
            }
        }

        //하나로 만들기
        var result = Movie()
        if(!audioTrack.isEmpty()){
            result.addTrack(AppendTrack(*audioTrack.toTypedArray()))
        }

//        val h264Track = H264TrackImpl(activity.applicationContext.assets.open("btm.mp3"))

        if(!videoTrack.isEmpty()){
            result.addTrack(AppendTrack(*videoTrack.toTypedArray()))
        }

        //저장하기
        var out = DefaultMp4Builder().build(result)

//        var file = File(getOutputMediaFile(false) + File.separator + "videoDiary.mp4")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fc = RandomAccessFile(config.savePhotosFolder + File.separator + "videoDiary" + timestamp + ".mp4", "rw").channel
        out.writeContainer(fc)
        fc.close()
    }

    private fun updateDateInView(cal : Calendar, view : TextView){
        //end가 start보다 나중에 오도록 예외처리
        val myFormat = "MM/dd/yyyy"
        val sdf = SimpleDateFormat(myFormat, Locale.US)
        view!!.text = sdf.format(cal.time)
    }

    private fun calToString(cal : Calendar) : String{
        val myFormat = "MM/dd/yyyy"
        val sdf = SimpleDateFormat(myFormat, Locale.US)
        return sdf.format(cal.time)
    }
}