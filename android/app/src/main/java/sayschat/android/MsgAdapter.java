package sayschat.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 消息列表适配器：按消息类型着色，时间取消息到达时刻。 */
public final class MsgAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final List<ChatMsg> list;
    private final int helpColor;
    private final int attachmentColor;
    private final SimpleDateFormat tf = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat hf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    public MsgAdapter(Context context, List<ChatMsg> list) {
        this.inflater = LayoutInflater.from(context);
        this.list = list;
        this.helpColor = context.getResources().getColor(R.color.success);
        this.attachmentColor = context.getResources().getColor(R.color.attachment_color);
    }

    @Override
    public int getCount() {
        return list.size();
    }

    @Override
    public Object getItem(int position) {
        return list.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.item_msg, parent, false);
        }
        ChatMsg m = list.get(position);
        TextView header = (TextView) view.findViewById(R.id.tvHeader);
        TextView body = (TextView) view.findViewById(R.id.tvBody);
        ImageView attachment = (ImageView) view.findViewById(R.id.ivAttachment);
        attachment.setVisibility(View.GONE);
        attachment.setImageDrawable(null);
        // 文件消息需要让 ListView 收到点击以打开保存位置；普通文本保持可选中复制。
        body.setTextIsSelectable(m.kind != ChatMsg.KIND_FILE);

        if (m.kind == ChatMsg.KIND_HELP) {
            header.setVisibility(View.GONE);
            body.setText(m.body);
            body.setTextColor(helpColor);
            body.setTextSize(13f);
            body.setTypeface(Typeface.DEFAULT);
            return view;
        }

        if (m.kind == ChatMsg.KIND_SYS) {
            header.setVisibility(View.GONE);
            body.setText(m.body);
            body.setTextColor(Color.rgb(0x88, 0x88, 0x88));
            body.setTextSize(13f);
            body.setTypeface(Typeface.DEFAULT);
            return view;
        }

        header.setVisibility(View.VISIBLE);
        header.setText("[" + (m.historical ? hf : tf).format(new Date(m.timestamp))
                + "] " + m.header);
        header.setTextColor(Color.rgb(0x88, 0x88, 0x88));
        body.setText(m.body);
        body.setTextSize(15f);
        body.setTypeface(Typeface.DEFAULT);
        if (m.kind == ChatMsg.KIND_FILE) {
            body.setTextColor(attachmentColor);
            body.setTextSize(14f);
            if (m.thumbnail != null) {
                attachment.setVisibility(View.VISIBLE);
                attachment.setImageBitmap(m.thumbnail);
            }
            return view;
        }
        switch (m.kind) {
            case ChatMsg.KIND_SELF:
                body.setTextColor(Color.rgb(0x15, 0x65, 0xC0));
                break;
            case ChatMsg.KIND_TELL:
                body.setTextColor(Color.rgb(0x6A, 0x1B, 0x9A));
                header.setTextColor(Color.rgb(0x8E, 0x24, 0xAA));
                break;
            case ChatMsg.KIND_SAYS:
                body.setTextColor(Color.rgb(0xC6, 0x28, 0x28));
                body.setTypeface(Typeface.DEFAULT_BOLD);
                header.setTextColor(Color.rgb(0xC6, 0x28, 0x28));
                break;
            default:
                body.setTextColor(Color.rgb(0x22, 0x22, 0x22));
                header.setTextColor(Color.rgb(0x88, 0x88, 0x88));
        }
        return view;
    }
}
