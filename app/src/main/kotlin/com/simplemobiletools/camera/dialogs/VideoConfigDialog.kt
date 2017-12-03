package com.simplemobiletools.camera.dialogs

import android.app.DatePickerDialog
import android.graphics.Color
import android.support.v7.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.DatePicker
import android.widget.TextView
import com.simplemobiletools.camera.R
import com.simplemobiletools.camera.activities.SimpleActivity
import com.simplemobiletools.commons.extensions.setupDialogStuff
import kotlinx.android.synthetic.main.dialog_set_video_dialog.view.*
import java.text.SimpleDateFormat
import java.util.*


/**
 * Created by jeeyunlee on 18/11/2017.
 */
class VideoConfigDialog(val activity: SimpleActivity, val startText: String, val endText : String, val callback: () -> Unit){
    var dialog: AlertDialog
    var curStartDate: String
    var curEndDate: String
    private var curStartDateView : TextView
    private var curEndDateView : TextView
    init {

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_set_video_dialog, null).apply {
            start_date_input.setOnClickListener{setSelectedDate(this, start_date_input)}
            end_date_input.setOnClickListener{setSelectedDate(this, end_date_input)}
        }

        curStartDate = startText
        curStartDateView = view.start_date_input
        curStartDateView.text = curStartDate

        curEndDate = endText
        curEndDateView = view.end_date_input
        curEndDateView.text = curEndDate
        dialog = AlertDialog.Builder(activity)
                .setPositiveButton("OK", null)
                .setOnDismissListener { callback() }
                .create().apply{
            activity.setupDialogStuff(view, this)
        }
    }

    private fun setSelectedDate(view: View, textView: TextView){

        var cal = Calendar.getInstance()
        val dateSetListener = object : DatePickerDialog.OnDateSetListener {
            override fun onDateSet(view: DatePicker, year: Int, monthOfYear: Int,
                                   dayOfMonth: Int) {
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, monthOfYear)
                cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                updateDateInView(cal, textView)
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

    private fun updateDateInView(cal : Calendar, view : TextView){
        val myFormat = "MM/dd/yyyy" // mention the format you need
        val sdf = SimpleDateFormat(myFormat, Locale.US)
        view!!.text = sdf.format(cal.getTime())
    }

}