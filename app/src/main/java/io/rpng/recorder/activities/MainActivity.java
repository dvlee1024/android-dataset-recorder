package io.rpng.recorder.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

import io.rpng.recorder.R;
import io.rpng.recorder.managers.CameraManager;
import io.rpng.recorder.managers.GPSManager;
import io.rpng.recorder.managers.IMUManager;
import io.rpng.recorder.views.AutoFitTextureView;

import static android.graphics.ImageFormat.NV21;


public class MainActivity extends AppCompatActivity {

    private static String TAG = "MainActivity";
    private static final int RESULT_SETTINGS = 1;
    private static final int RESULT_RESULT = 2;
    private static final int RESULT_INFO = 3;

    private static Intent intentSettings;
    private static Intent intentResults;

    private static ImageView camera2View;
    private AutoFitTextureView mTextureView;

    public static CameraManager mCameraManager;
    public static IMUManager mImuManager;
    public static GPSManager mGpsManager;
    private static SharedPreferences sharedPreferences;


    // Variables for the current state
    public static boolean is_recording;
    public static String folder_name;

    public static long timeName = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        // Pass to super
        super.onCreate(savedInstanceState);

        // Create our layout
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Add our listeners
        this.addButtonListeners();

        // Get our surfaces
        camera2View = (ImageView) findViewById(R.id.camera2_preview);
        mTextureView = (AutoFitTextureView) findViewById(R.id.camera2_texture);

        // Create the camera manager
        mCameraManager = new CameraManager(this, mTextureView, camera2View);
        mImuManager = new IMUManager(this);
        //mGpsManager = new GPSManager(this);

        // Set our shared preferences
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        // Build the result activities for later
        intentSettings = new Intent(this, SettingsActivity.class);
        intentResults = new Intent(this, ResultsActivity.class);

        // Set the state so that we are not recording
        folder_name = "";
        is_recording = false;

        // Lets by default launch into the settings view
        startActivityForResult(intentSettings, RESULT_SETTINGS);

    }

    private void addButtonListeners() {

        // We we want to "capture" the current grid, we should record the current corners
        Button button_record = (Button) findViewById(R.id.button_record);
        button_record.setOnClickListener( new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // If we are not recording we should start it
                if(!is_recording) {
                    // Set our folder name

                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyMMdd_HHmmss");
                    folder_name = dateFormat.format(new Date());

                    // Also change the text on the button so that it turns into the stop button
                    Button button_record = (Button) findViewById(R.id.button_record);
                    button_record.setText("Stop Recording");

                    startTimestampRef = -1;
                    // Trigger the recording by changing the recording boolean
                    is_recording = true;

                    timeName = SystemClock.elapsedRealtime() / 1000;
                }
                // Else we can assume we pressed the "stop recording" button
                else {
                    // Just reset the recording button
                    is_recording = false;

                    // Also change the text on the button so that it turns into the start button
                    Button button_record = (Button) findViewById(R.id.button_record);
                    button_record.setText("Start Recording");

                    // Start the result activity
                    //startActivityForResult(intentResults, RESULT_RESULT);
                }
            }
        });
    }

    private void delDir(String path){
        File dir=new File(path);
        if(dir.exists()){
            File[] tmp=dir.listFiles();
            for(int i=0;i<tmp.length;i++){
                if(tmp[i].isDirectory()){
                    delDir(path+"/"+tmp[i].getName());
                }
                else {
                    tmp[i].delete();
                }
            }
            dir.delete();
        }
    }

    private void clearDir(String path){
        File dir=new File(path);
        if(dir.exists()){
            File[] tmp=dir.listFiles();
            for(int i=0;i<tmp.length;i++){
                if(tmp[i].isDirectory()){
                    delDir(path+"/"+tmp[i].getName());
                }
                else {
                    tmp[i].delete();
                }
            }
        }
    }

    @Override
    public void onResume() {
        // Pass to our super
        super.onResume();
        // Start the background thread
        mCameraManager.startBackgroundThread();
        // Open the camera
        // This should take care of the permissions requests
        if (mTextureView.isAvailable()) {
            mCameraManager.openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mCameraManager.mSurfaceTextureListener);
        }

        // Register the listeners
        mImuManager.register();

        // Start background thread
        //mGpsManager.startBackgroundThread();
        // Register google services
        //mGpsManager.register();
    }

    @Override
    public void onPause() {

        // Stop background thread
        mCameraManager.stopBackgroundThread();
        // Close our camera, note we will get permission errors if we try to reopen
        // And we have not closed the current active camera
        mCameraManager.closeCamera();

        // Unregister the listeners
        mImuManager.unregister();

        // Stop background thread
        //mGpsManager.stopBackgroundThread();
        // Remove gps listener
        //mGpsManager.unregister();

        // Call the super
        super.onPause();
    }

    static long startTimestampRef = -1;
    // Taken from OpenCamera project
    // URL: https://github.com/almalence/OpenCamera/blob/master/src/com/almalence/opencam/cameracontroller/Camera2Controller.java#L3455
    public final static ImageReader.OnImageAvailableListener imageAvailableListener = new ImageReader.OnImageAvailableListener() {
        private long timeLast,timeCur;
        private long sleepTime = 60;
        private static final long MS_PER_FRAME = 100;
        @Override
        public void onImageAvailable(ImageReader ir) {
            // Contrary to what is written in Aptina presentation acquireLatestImage is not working as described
            // Google: Also, not working as described in android docs (should work the same as acquireNextImage in
            // our case, but it is not)
            // Image im = ir.acquireLatestImage();

            // Get the next image from the queue
            Image image = ir.acquireNextImage();
            timeLast = timeCur;
            timeCur = System.nanoTime();
            long lastFrameSpendTime = (timeCur - timeLast)/1000/1000;

            // Collection of bytes of the image



            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
            ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
            ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

            int yCount = yBuffer.remaining();
            int uCount = uBuffer.remaining();
            int UVPixelStride = image.getPlanes()[1].getPixelStride();

            byte[] rez = new byte[image.getWidth()*image.getHeight() * 3 /2];
            yBuffer.get(rez, 0, yCount);

            // Convert to NV21 format
            // https://github.com/bytedeco/javacv/issues/298#issuecomment-169100091
            if(UVPixelStride == 1){
                for(int y = 0; y < uCount; y ++)
                {
                    int id = y * 2 + yCount;
                    rez[id] = vBuffer.get(y);
                    rez[id+1] = uBuffer.get(y);
                }
            } else if(UVPixelStride == 2){
                vBuffer.get(rez, yCount, uCount);
            }

            // Byte output stream, so we can save the file
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // Create YUV image file
            YuvImage yuvImage = new YuvImage(rez, NV21, image.getWidth(), image.getHeight(), null);
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 90, out);
            byte[] imageBytes = out.toByteArray();

            // Display for the end user
            Bitmap bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            MainActivity.camera2View.setImageBitmap(bmp);

            // Save the file (if enabled)
            // http://stackoverflow.com/a/9006098
            if(MainActivity.is_recording) {

                // Current timestamp of the event
                // TODO: See if we can use image.getTimestamp()
//                long timestamp = new Date().getTime();
                if(startTimestampRef <= 0){
                    startTimestampRef = SystemClock.elapsedRealtimeNanos() - image.getTimestamp();
                }
                long timestamp = image.getTimestamp() + startTimestampRef;

                // Create folder name
                String filename = "image.txt";
//                String path = Environment.getExternalStorageDirectory().getAbsolutePath()
//                        + "/dataset_recorder/" + MainActivity.folder_name + "/";
                String path = "/sdcard/dataset/" + timeName + "/";

                // Create export file
                new File(path).mkdirs();
                File dest = new File(path + filename);

                try {
                    // If the file does not exist yet, create it
                    if(!dest.exists())
                        dest.createNewFile();

                    // The true will append the new data
                    BufferedWriter writer = new BufferedWriter(new FileWriter(dest, true));

                    // Master string of information
                    String sdata = timestamp + ",images/" + timestamp + ".jpeg";

                    // Appends the string to the file and closes
                    writer.write(sdata + "\n");
                    writer.flush();
                    writer.close();
                }
                // Ran into a problem writing to file
                catch(IOException ioe) {
                    System.err.println("IOException: " + ioe.getMessage());
                }

                String picDirPath = path + "/images/";
                // Create folder name
                filename = timestamp + ".jpeg";
//                path = Environment.getExternalStorageDirectory().getAbsolutePath()
//                        + "/dataset_recorder/" + MainActivity.folder_name + "/images/";
//                path = "/sdcard/dataset/" + timeName + "/";

                // Create export file
                new File(picDirPath).mkdirs();
                dest = new File(picDirPath + filename);

                // Export the file to disk
                try {
                    FileOutputStream output = new FileOutputStream(dest);
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, output);
                    output.flush();
                    output.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // Make sure we close the image


            try {
                if(lastFrameSpendTime > MS_PER_FRAME) {
                    sleepTime-=2;
                } else {
                    sleepTime++;
                }
                if(sleepTime < MS_PER_FRAME && sleepTime > 0){
                    Thread.sleep(sleepTime);
                }

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            Log.i("dvlee","last spend "+lastFrameSpendTime + "ms, sleep " + sleepTime);

            image.close();
        }
    };

    public static byte[] getBytesFromImageAsType(Image image, int type) {
        try {
            //获取源数据，如果是YUV格式的数据planes.length = 3
            //plane[i]里面的实际数据可能存在byte[].length <= capacity (缓冲区总大小)
            final Image.Plane[] planes = image.getPlanes();

            //数据有效宽度，一般的，图片width <= rowStride，这也是导致byte[].length <= capacity的原因
            // 所以我们只取width部分
            int width = image.getWidth();
            int height = image.getHeight();

            //此处用来装填最终的YUV数据，需要1.5倍的图片大小，因为Y U V 比例为 4:1:1
            byte[] yuvBytes = new byte[width * height * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8];
            //目标数组的装填到的位置
            int dstIndex = 0;

            //临时存储uv数据的
            byte uBytes[] = new byte[width * height / 4];
            byte vBytes[] = new byte[width * height / 4];
            int uIndex = 0;
            int vIndex = 0;

            int pixelsStride, rowStride;
            for (int i = 0; i < planes.length; i++) {
                pixelsStride = planes[i].getPixelStride();
                rowStride = planes[i].getRowStride();

                ByteBuffer buffer = planes[i].getBuffer();

                //如果pixelsStride==2，一般的Y的buffer长度=640*480，UV的长度=640*480/2-1
                //源数据的索引，y的数据是byte中连续的，u的数据是v向左移以为生成的，两者都是偶数位为有效数据
                byte[] bytes = new byte[buffer.capacity()];
                buffer.get(bytes);

                int srcIndex = 0;
                if (i == 0) {
                    //直接取出来所有Y的有效区域，也可以存储成一个临时的bytes，到下一步再copy
                    for (int j = 0; j < height; j++) {
                        System.arraycopy(bytes, srcIndex, yuvBytes, dstIndex, width);
                        srcIndex += rowStride;
                        dstIndex += width;
                    }
                } else if (i == 1) {
                    //根据pixelsStride取相应的数据
                    for (int j = 0; j < height / 2; j++) {
                        for (int k = 0; k < width / 2; k++) {
                            uBytes[uIndex++] = bytes[srcIndex];
                            srcIndex += pixelsStride;
                        }
                        if (pixelsStride == 2) {
                            srcIndex += rowStride - width;
                        } else if (pixelsStride == 1) {
                            srcIndex += rowStride - width / 2;
                        }
                    }
                } else if (i == 2) {
                    //根据pixelsStride取相应的数据
                    for (int j = 0; j < height / 2; j++) {
                        for (int k = 0; k < width / 2; k++) {
                            vBytes[vIndex++] = bytes[srcIndex];
                            srcIndex += pixelsStride;
                        }
                        if (pixelsStride == 2) {
                            srcIndex += rowStride - width;
                        } else if (pixelsStride == 1) {
                            srcIndex += rowStride - width / 2;
                        }
                    }
                }
            }

            image.close();

            //根据要求的结果类型进行填充
            switch (type) {
//                case YUV420P:
//                    System.arraycopy(uBytes, 0, yuvBytes, dstIndex, uBytes.length);
//                    System.arraycopy(vBytes, 0, yuvBytes, dstIndex + uBytes.length, vBytes.length);
//                    break;
//                case YUV420SP:
//                    for (int i = 0; i < vBytes.length; i++) {
//                        yuvBytes[dstIndex++] = uBytes[i];
//                        yuvBytes[dstIndex++] = vBytes[i];
//                    }
//                    break;
                case NV21:

                    break;
            }

            for (int i = 0; i < vBytes.length; i++) {
                yuvBytes[dstIndex++] = vBytes[i];
                yuvBytes[dstIndex++] = uBytes[i];
            }
            return yuvBytes;
        } catch (final Exception e) {
            if (image != null) {
                image.close();
            }
            Log.i(TAG, e.toString());
        }
        return null;
    }

    private static byte[]  getYUV420Data(ByteBuffer yc, ByteBuffer uc, ByteBuffer vc,
                         int w,  int h,
                         int YRowStride,  int UVRowStride,  int UVPixelStride)
    {
//        Log.i("dvlee","y data:"+yc.remaining()+" uv:" + uc.remaining());
//        Log.i("dvlee","image info: " + YRowStride + ":" + UVRowStride + ":" + UVPixelStride);
        int dataSize = w*h*3/2;
        byte []rez = new byte[dataSize];
        int count=0;

        int yCount = yc.remaining();
        int uCount = vc.remaining();

        yc.get(rez, 0, yCount);

        if(UVPixelStride == 1){
            for(int y = 0; y < uCount; y ++)
            {
                int id = y * 2 + yCount;
                rez[id] = vc.get(y);
                rez[id+1] = uc.get(y);
            }
        } else if(UVPixelStride == 2){
            for(int y = 0; y < vc.remaining(); y ++)
            {
                int id = yc.remaining() + y;
                rez[id] = vc.get(y);
            }
        }
        return rez;
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {

            // Disable the current recording session
            is_recording = false;

            // Also change the text on the button so that it turns into the start button
            Button button_record = (Button) findViewById(R.id.button_record);
            button_record.setText("Start Recording");

            // Start the settings activity
            Intent i = new Intent(this, SettingsActivity.class);
            startActivityForResult(i, RESULT_SETTINGS);

            return true;
        }

        if (id == R.id.action_info) {

            // Disable the current recording session
            is_recording = false;

            // Also change the text on the button so that it turns into the start button
            Button button_record = (Button) findViewById(R.id.button_record);
            button_record.setText("Start Recording");

            // Start the settings activity
            Intent i = new Intent(this, InfoActivity.class);
            startActivityForResult(i, RESULT_INFO);

            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {

            // Call back from end of settings activity
            case RESULT_SETTINGS:

                // The settings have changed, so reset the calibrator
                //mCameraCalibrator.clearCorners();

                // Update the textview with starting values
                //camera2Captured.setText("Capture Success: 0\nCapture Tries: 0");

                break;

            // Call back from end of settings activity
            case RESULT_RESULT:

                // The settings have changed, so reset the calibrator
                //mCameraCalibrator.clearCorners();

                // Update the textview with starting values
                //camera2Captured.setText("Capture Success: 0\nCapture Tries: 0");

                break;

        }

    }
}
