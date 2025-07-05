package com.example.appbt;

import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.ContactViewHolder> {

    public interface OnItemActionListener {
        void onEdit(int position);
        void onDelete(int position);
    }

    private final List<Contact> contacts;
    private final OnItemActionListener listener;

    public ContactAdapter(List<Contact> contacts, OnItemActionListener listener) {
        this.contacts = contacts;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ContactViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.contact_item, parent, false);
        return new ContactViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ContactViewHolder holder, int position) {
        Contact contact = contacts.get(position);
        holder.nameText.setText(contact.getName());
        holder.numberText.setText(contact.getNumber());

        holder.editBtn.setOnClickListener(v -> {
            if (listener != null) listener.onEdit(position);
        });

        holder.deleteBtn.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDelete(position);
            }
        });

    }

    @Override
    public int getItemCount() {
        return contacts.size();
    }

    static class ContactViewHolder extends RecyclerView.ViewHolder {
        TextView nameText, numberText;
        ImageButton editBtn, deleteBtn;

        public ContactViewHolder(@NonNull View itemView) {
            super(itemView);
            nameText = itemView.findViewById(R.id.contactName);
            numberText = itemView.findViewById(R.id.contactNumber);
            editBtn = itemView.findViewById(R.id.editBtn);
            deleteBtn = itemView.findViewById(R.id.deleteBtn);
        }
    }
}
