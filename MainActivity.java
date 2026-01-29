package uk.ac.wlv.blogclient;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.concurrent.ExecutionException;

import uk.ac.wlv.blogclient.data.model.Post;
import uk.ac.wlv.blogclient.data.repo.PostRepository;
import uk.ac.wlv.blogclient.ui.PostAdapter;

public class MainActivity extends AppCompatActivity {

    private PostRepository repo;
    private PostAdapter adapter;

    private EditText etSearch;
    private Button btnAdd;
    private Button btnDeleteSelected;

    // Image picker for Add dialog
    private ActivityResultLauncher<String[]> pickImageLauncher;
    private Uri selectedImageUri;
    private ImageView dialogImagePreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        repo = new PostRepository(this);

        etSearch = findViewById(R.id.etSearch);
        btnAdd = findViewById(R.id.btnAdd);
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected);

        RecyclerView rv = findViewById(R.id.rvPosts);
        rv.setLayoutManager(new LinearLayoutManager(this));

        adapter = new PostAdapter(
                post -> {
                    Intent i = new Intent(this, PostDetailActivity.class);
                    i.putExtra(PostDetailActivity.EXTRA_POST_ID, post.id);
                    startActivity(i);
                },
                count -> {
                    btnDeleteSelected.setEnabled(count > 0);
                    btnDeleteSelected.setText(
                            count > 0 ? "Delete Selected (" + count + ")" : "Delete Selected"
                    );
                }
        );

        rv.setAdapter(adapter);

        btnDeleteSelected.setEnabled(false);
        btnDeleteSelected.setOnClickListener(v -> deleteSelected());

        // Image picker (Gallery only for dialog)
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                        } catch (SecurityException ignored) {}

                        selectedImageUri = uri;
                        if (dialogImagePreview != null) {
                            dialogImagePreview.setImageURI(uri);
                        }
                    }
                }
        );

        btnAdd.setOnClickListener(v -> showAddDialog());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                loadPosts(s.toString());
            }
        });

        loadPosts("");
    }

    private void loadPosts(String query) {
        new Thread(() -> {
            try {
                List<Post> posts = (query == null || query.trim().isEmpty())
                        ? repo.getAll().get()
                        : repo.search(query.trim()).get();

                runOnUiThread(() -> adapter.setItems(posts));

            } catch (ExecutionException | InterruptedException e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Load failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    private void showAddDialog() {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_add_post, null);

        EditText etTitle = dialogView.findViewById(R.id.etTitle);
        EditText etBody = dialogView.findViewById(R.id.etBody);
        dialogImagePreview = dialogView.findViewById(R.id.ivPreview);
        Button btnGallery = dialogView.findViewById(R.id.btnGallery);

        selectedImageUri = null;
        dialogImagePreview.setImageDrawable(null);

        btnGallery.setOnClickListener(v ->
                pickImageLauncher.launch(new String[]{"image/*"})
        );

        new AlertDialog.Builder(this)
                .setTitle("New Post")
                .setView(dialogView)
                .setPositiveButton("Save", (d, which) -> {
                    String title = etTitle.getText().toString().trim();
                    String body = etBody.getText().toString().trim();

                    if (body.isEmpty()) {
                        Toast.makeText(this, "Body is required", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    long now = System.currentTimeMillis();
                    Post p = new Post(
                            title,
                            body,
                            selectedImageUri == null ? null : selectedImageUri.toString(),
                            now,
                            now,
                            false,
                            null
                    );

                    new Thread(() -> {
                        try {
                            repo.insert(p).get();
                            runOnUiThread(() -> {
                                loadPosts(etSearch.getText().toString());
                                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                            });
                        } catch (ExecutionException | InterruptedException e) {
                            runOnUiThread(() ->
                                    Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
                            );
                        }
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteSelected() {
        List<Long> ids = adapter.getSelectedIds();
        if (ids.isEmpty()) return;

        new AlertDialog.Builder(this)
                .setTitle("Delete selected?")
                .setMessage("This will delete " + ids.size() + " post(s).")
                .setPositiveButton("Delete", (d, which) -> {
                    new Thread(() -> {
                        try {
                            repo.deleteByIds(ids).get();
                            runOnUiThread(() -> {
                                adapter.clearSelection();
                                loadPosts(etSearch.getText().toString());
                                Toast.makeText(this, "Deleted selected", Toast.LENGTH_SHORT).show();
                            });
                        } catch (Exception e) {
                            runOnUiThread(() ->
                                    Toast.makeText(this, "Delete failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
                            );
                        }
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPosts(etSearch.getText().toString());
    }
}
