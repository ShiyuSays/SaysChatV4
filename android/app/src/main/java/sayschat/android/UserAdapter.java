package sayschat.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;
import java.util.Map;

/**
 * 左侧在线用户适配器。
 * 管理员显示为“用户名[管理员]”，管理员头衔使用强调色加粗。
 */
public final class UserAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final List<String> names;
    private final Map<String, Boolean> admins;
    private final int adminColor;
    private final int normalColor;

    public UserAdapter(Context context, List<String> names, Map<String, Boolean> admins) {
        this.inflater = LayoutInflater.from(context);
        this.names = names;
        this.admins = admins;
        this.adminColor = context.getResources().getColor(R.color.admin_color);
        this.normalColor = Color.rgb(0x33, 0x33, 0x33);
    }

    @Override
    public int getCount() {
        return names.size();
    }

    @Override
    public Object getItem(int position) {
        return names.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView view;
        if (convertView == null) {
            view = (TextView) inflater.inflate(R.layout.item_user, parent, false);
        } else {
            view = (TextView) convertView;
        }
        String name = names.get(position);
        boolean isAdmin = Boolean.TRUE.equals(admins.get(name));
        SpannableStringBuilder text = new SpannableStringBuilder(name);
        if (isAdmin) {
            int start = text.length();
            text.append("[管理员]");
            text.setSpan(new ForegroundColorSpan(adminColor), start, text.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new StyleSpan(Typeface.BOLD), 0, start,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(text);
        view.setTextColor(normalColor);
        return view;
    }
}
