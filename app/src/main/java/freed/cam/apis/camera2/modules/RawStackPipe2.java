package freed.cam.apis.camera2.modules;

import android.os.Build;
import android.os.Handler;


import androidx.annotation.RequiresApi;

import com.troop.freedcam.R;

import java.io.File;
import java.io.IOException;

import freed.cam.apis.basecamera.CameraWrapperInterface;
import freed.cam.apis.basecamera.parameters.AbstractParameter;
import freed.cam.apis.camera2.modules.helper.ImageCaptureHolder;
import freed.cam.ui.themesample.handler.UserMessageHandler;
import freed.dng.DngProfile;
import freed.jni.ExifInfo;
import freed.jni.RawStack;
import freed.jni.RawToDng;
import freed.settings.SettingKeys;
import freed.settings.SettingsManager;
import freed.utils.Log;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class RawStackPipe2 extends PictureModuleApi2 {

    private final String tmpFolder;
    private final String TAG = RawStackPipe2.class.getSimpleName();

    public RawStackPipe2(CameraWrapperInterface cameraUiWrapper, Handler mBackgroundHandler, Handler mainHandler) {
        super(cameraUiWrapper, mBackgroundHandler, mainHandler);
        name = cameraUiWrapper.getActivityInterface().getStringFromRessources(R.string.module_stacking2);
        tmpFolder = cameraUiWrapper.getActivityInterface().getStorageHandler().getFreedcamFolder()+"/tmp/";
        File tmp = new File(tmpFolder);
        if (!tmp.exists())
            tmp.mkdirs();
    }

    @Override
    public String LongName() {
        return "HDR+2";
    }

    @Override
    public String ShortName() {
        return "HDR+2";
    }

    @Override
    public void InitModule() {

        SettingsManager.get(SettingKeys.lastPictureFormat).set(SettingsManager.get(SettingKeys.PictureFormat).get());
        SettingsManager.get(SettingKeys.PictureFormat).set(SettingsManager.getInstance().getResString(R.string.pictureformat_bayer));

        super.InitModule();
        cameraUiWrapper.parametersHandler.get(SettingKeys.PictureFormat).setViewState(AbstractParameter.ViewState.Hidden);
        cameraUiWrapper.parametersHandler.get(SettingKeys.M_Burst).SetValue(14,true);
    }

    @Override
    public void DestroyModule() {
        cameraUiWrapper.parametersHandler.get(SettingKeys.M_Burst).SetValue(0,true);
        cameraUiWrapper.parametersHandler.get(SettingKeys.PictureFormat).setViewState(AbstractParameter.ViewState.Visible);
        SettingsManager.get(SettingKeys.PictureFormat).set(SettingsManager.get(SettingKeys.lastPictureFormat).get());
        super.DestroyModule();
    }

    protected String getFileString()
    {
        return tmpFolder +"burst"+BurstCounter.getImageCaptured();
    }


    @Override
    public void DoWork() {
        imageStored=0;
        super.DoWork();
    }

    /**
     * Reset the capturesession to preview
     *
     */
    private int imageStored;
    @Override
    public void internalFireOnWorkDone(File file)
    {
        mBackgroundHandler.post(() ->{
        Log.d(TAG, "internalFireOnWorkDone"  + " burstCount/imagecount:" + BurstCounter.getBurstCount() + "/" +BurstCounter.getImageCaptured());
        imageStored++;
        if (workFinishEventsListner != null)
            workFinishEventsListner.internalFireOnWorkDone(file);
        else {
            Log.d(TAG, "internalFireOnWorkDone BurstCount:" + BurstCounter.getBurstCount() + " imageCount:" + BurstCounter.getImageCaptured() + " images stored: " + imageStored);
            if (BurstCounter.getBurstCount() >= BurstCounter.getImageCaptured()) {
                filesSaved.add(file);
                Log.d(TAG, "internalFireOnWorkDone Burst addFile");
            }
            if (BurstCounter.getBurstCount() == imageStored) {
                Log.d(TAG, "start stack");
                startStack();
            }

        }
        });
    }

    @Override
    protected void finishCapture() {
        super.finishCapture();

    }

    @Override
    public void onRdyToSaveImg(ImageCaptureHolder holder) {
        super.onRdyToSaveImg(holder);
    }

    private static volatile boolean stacking = false;
    private void startStack()
    {
        if (BurstCounter.getBurstCount() == BurstCounter.getImageCaptured()) {
            Log.d(TAG, "internalFireOnWorkDone Burst done");

            if (stacking)
                return;

            new Thread(() -> {
                stacking = true;
                try {
                    byte[] input = RawToDng.readFile(filesSaved.get(0));
                    //MappedByteBuffer input = RawToDng.readFileToMemoryMap(filesSaved.get(0));
                    if (input != null)
                        Log.d(TAG, "input size: " + input.length);
                    RawStack rawStack = new RawStack();
                    int upshift;
                    if (SettingsManager.get(SettingKeys.forceRawToDng).get())
                    {
                        //if input data is 12bit only scale it by 2, else it would clip high/lights = 14bit
                        if (SettingsManager.get(SettingKeys.support12bitRaw).get())
                            upshift = 2;
                        else //shift 10bit input up to 14bit
                            upshift = 4;
                    }
                    else //use stock dngcreator, dont scale it up till we found a way to manipulate black and whitelvl from the capture result
                        upshift = 0;

                    DngProfile dngProfile;
                    if (SettingsManager.get(SettingKeys.useCustomMatrixOnCamera2).get() && SettingsManager.getInstance().getDngProfilesMap().get(input.length) != null) {
                        dngProfile = SettingsManager.getInstance().getDngProfilesMap().get(input.length);
                        if (upshift > 0)
                        {
                            dngProfile.setBlackLevel(dngProfile.getBlacklvl() << upshift);
                            dngProfile.setWhiteLevel(dngProfile.getWhitelvl() << upshift);
                        }
                    }
                    else
                        dngProfile = currentCaptureHolder.getDngProfile(upshift);


                    if (SettingsManager.get(SettingKeys.MATRIX_SET).get() == null ||SettingsManager.get(SettingKeys.MATRIX_SET).get().equals(SettingsManager.getInstance().getResString(R.string.off_)))
                        dngProfile.matrixes = currentCaptureHolder.getMatrix();
                    else
                        dngProfile.matrixes = SettingsManager.getInstance().getMatrixesMap().get(SettingsManager.get(SettingKeys.MATRIX_SET).get());

                    if(dngProfile.matrixes == null)
                        dngProfile.matrixes = currentCaptureHolder.getMatrix();

                    rawStack.setShift(upshift);
                    rawStack.setFirstFrame(input, dngProfile.getWidth(), dngProfile.getHeight());

                    for (int i = 1; i < filesSaved.size(); i++) {
                        byte[] nextinput = RawToDng.readFile(filesSaved.get(i));
                        rawStack.stackNextFrame(nextinput);
                        Log.d(TAG,"Stacked:" + i);
                        //nextinput.clear();
                        UserMessageHandler.sendMSG("Stacked:" + i, false);
                    }


                    ExifInfo exifInfo = currentCaptureHolder.getExifInfo();
                    String fileout = cameraUiWrapper.getActivityInterface().getStorageHandler().getNewFilePath(SettingsManager.getInstance().GetWriteExternal(), "") + ".dng";
                    rawStack.saveDng(dngProfile, dngProfile.matrixes, fileout, exifInfo);
                    fireOnWorkFinish(new File(fileout));
                    for (File filetodel : filesSaved) {
                        filetodel.delete();
                    }
                    filesSaved.clear();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                stacking = false;
            }).start();


        }
    }
}
