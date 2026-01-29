package uk.ac.wlv.blogclient;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import uk.ac.wlv.blogclient.data.model.Post;
import uk.ac.wlv.blogclient.data.repo.PostRepository;

public class PostDetailActivity extends AppCompatActivity {

    public static final String EXTRA_POST_ID = "post_id";

    private PostRepository repo;
    private long postId;
    private Post loadedPost;

    private TextView tvTitle, tvBody, tvStatus;
    private ImageView ivImage;

    private Button btnUpload;

    // Realtime Database reference
    private DatabaseReference postsRef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_post_detail);

        repo = new PostRepository(this);

        // Realtime DB: points to /posts
        postsRef = FirebaseDatabase.getInstance().getReference("posts");

        tvTitle = findViewById(R.id.tvTitle);
        tvBody = findViewById(R.id.tvBody);
        tvStatus = findViewById(R.id.tvStatus);
        ivImage = findViewById(R.id.ivImage);

        Button btnEdit = findViewById(R.id.btnEdit);
        Button btnShare = findViewById(R.id.btnShare);
        Button btnDelete = findViewById(R.id.btnDelete);
        btnUpload = findViewById(R.id.btnUpload);
        Button btnBack = findViewById(R.id.btnBack);

        postId = getIntent().getLongExtra(EXTRA_POST_ID, -1);
        if (postId <= 0) {
            Toast.makeText(this, "Invalid post id", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        btnEdit.setOnClickListener(v -> {
            Intent i = new Intent(this, PostEditActivity.class);
            i.putExtra(PostEditActivity.EXTRA_POST_ID, postId);
            startActivity(i);
        });

        btnShare.setOnClickListener(v -> share());
        btnUpload.setOnClickListener(v -> uploadPostRealtime());
        btnDelete.setOnClickListener(v -> confirmDelete());
        btnBack.setOnClickListener(v -> finish());

        loadPost();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPost(); // refresh after edit
    }

    private void loadPost() {
        new Thread(() -> {
            try {
                loadedPost = repo.getById(postId).get();

                runOnUiThread(() -> {
                    if (loadedPost == null) {
                        Toast.makeText(this, "Post not found", Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }

                    tvTitle.setText(
                            (loadedPost.title == null || loadedPost.title.trim().isEmpty())
                                    ? "(No title)"
                                    : loadedPost.title
                    );

                    tvBody.setText(loadedPost.body == null ? "" : loadedPost.body);
                    tvStatus.setText(loadedPost.uploaded ? "Status: Uploaded" : "Status: Offline");

                    // Upload button state
                    btnUpload.setEnabled(!loadedPost.uploaded);

                    // IMAGE (safe)
                    if (loadedPost.imageUri != null && !loadedPost.imageUri.trim().isEmpty()) {
                        try {
                            Uri uri = Uri.parse(loadedPost.imageUri);
                            ivImage.setVisibility(View.VISIBLE);
                            ivImage.setImageURI(uri);
                        } catch (SecurityException se) {
                            ivImage.setVisibility(View.GONE);
                            Toast.makeText(this,
                                    "Cannot access saved image. Please reselect it.",
                                    Toast.LENGTH_LONG).show();
                        }
                    } else {
                        ivImage.setVisibility(View.GONE);
                    }
                });

            } catch (ExecutionException | InterruptedException e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Load failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    private void share() {
        if (loadedPost == null) return;

        String text = (loadedPost.title == null ? "" : loadedPost.title + "\n\n")
                + (loadedPost.body == null ? "" : loadedPost.body);

        Intent i = new Intent(Intent.ACTION_SEND);

        if (loadedPost.imageUri != null && !loadedPost.imageUri.trim().isEmpty()) {
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_STREAM, Uri.parse(loadedPost.imageUri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.putExtra(Intent.EXTRA_TEXT, text);
        } else {
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
        }

        startActivity(Intent.createChooser(i, "Share post"));
    }

    private void uploadPostRealtime() {
        if (loadedPost == null) return;

        if (loadedPost.uploaded) {
            Toast.makeText(this, "Already uploaded", Toast.LENGTH_SHORT).show();
            return;
        }

        btnUpload.setEnabled(false);
        Toast.makeText(this, "Uploading...", Toast.LENGTH_SHORT).show();

        String key = postsRef.push().getKey();
        if (key == null) {
            btnUpload.setEnabled(true);
            Toast.makeText(this, "Upload key error", Toast.LENGTH_LONG).show();
            return;
        }

        // Data payload for realtime db
        Map<String, Object> data = new HashMap<>();
        data.put("localId", loadedPost.id);
        data.put("title", loadedPost.title == null ? "" : loadedPost.title);
        data.put("body", loadedPost.body == null ? "" : loadedPost.body);
        data.put("imageUri", loadedPost.imageUri == null ? "" : loadedPost.imageUri);
        data.put("uploadTime", System.currentTimeMillis());

        postsRef.child(key).setValue(data)
                .addOnSuccessListener(unused -> {
                    // Mark uploaded locally
                    new Thread(() -> {
                        try {
                            repo.markUploaded(loadedPost.id, true, key).get();

                            runOnUiThread(() -> {
                                loadedPost.uploaded = true;
                                loadedPost.uploadUrl = key;
                                tvStatus.setText("Status: Uploaded");
                                Toast.makeText(this, "Uploaded to Firebase", Toast.LENGTH_SHORT).show();
                                btnUpload.setEnabled(false);
                            });

                        } catch (Exception e) {
                            runOnUiThread(() -> {
                                btnUpload.setEnabled(true);
                                Toast.makeText(this, "Local update failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            });
                        }
                    }).start();
                })
                .addOnFailureListener(e -> {
                    btnUpload.setEnabled(true);
                    Toast.makeText(this, "Upload failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    private String formatDate(long millis) {
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(millis));
    }

    private void confirmDelete() {
        if (postId <= 0) {
            Toast.makeText(this, "Invalid post id", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Delete post?")
                .setMessage("This cannot be undone.")
                .setPositiveButton("Delete", (d, which) -> deletePost())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deletePost() {
        new Thread(() -> {
            try {
                int rows = repo.deleteById(postId).get();

                runOnUiThread(() -> {
                    if (rows > 0) {
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, "Post not found", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Delete failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }
}
