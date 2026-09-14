package com.parlour.management;
import android.app.Application;
import android.os.*;
import androidx.lifecycle.*;
import org.json.JSONObject;
public class AppViewModel extends AndroidViewModel {
 public final ApiRepository repository;public final MutableLiveData<Boolean> loading=new MutableLiveData<>(false);public final MutableLiveData<Result> results=new MutableLiveData<>();private final Handler main=new Handler(Looper.getMainLooper());
 public static class Result {public final String key;public final Object value;public final String error;public boolean consumed;Result(String key,Object value,String error){this.key=key;this.value=value;this.error=error;}}
 public AppViewModel(Application app){super(app);repository=new ApiRepository(app);}
 public void call(String key,String method,String path,Object body,String idempotency){loading.setValue(true);repository.request(method,path,body,idempotency,(value,error)->main.post(()->{loading.setValue(false);results.setValue(new Result(key,value,error));}));}
}
