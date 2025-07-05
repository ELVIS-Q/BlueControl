package com.example.appbt;

import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

public class PhoneNumberAdapter extends RecyclerView.Adapter<PhoneNumberAdapter.ViewHolder> {

    private final ArrayList<String> phoneNumbers;
    private final OnItemActionListener listener;

    public interface OnItemActionListener {
        void onEdit(int position);
        void onDelete(int position);
    }

    public PhoneNumberAdapter(ArrayList<String> phoneNumbers, OnItemActionListener listener) {
        this.phoneNumbers = phoneNumbers;
        this.listener = listener;
    }

    @NonNull
    @Override
    public PhoneNumberAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_2, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PhoneNumberAdapter.ViewHolder holder, int position) {
        String number = phoneNumbers.get(position);
        holder.text1.setText(number);
        holder.text2.setText("Editar | Eliminar");

        holder.text2.setOnClickListener(v -> showPopupMenu(v, position));
    }

    private void showPopupMenu(View view, int position) {
        PopupMenu popup = new PopupMenu(view.getContext(), view);
        popup.getMenu().add("Editar").setOnMenuItemClickListener(item -> { listener.onEdit(position); return true; });
        popup.getMenu().add("Eliminar").setOnMenuItemClickListener(item -> { listener.onDelete(position); return true; });
        popup.show();
    }

    @Override
    public int getItemCount() {
        return phoneNumbers.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView text1, text2;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            text1 = itemView.findViewById(android.R.id.text1);
            text2 = itemView.findViewById(android.R.id.text2);
        }
    }
}
