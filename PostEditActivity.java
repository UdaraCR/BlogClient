package uk.ac.wlv.blogclient;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

import uk.ac.wlv.blogclient.data.model.Post;
import uk.ac.wlv.blogclient.data.repo.PostRepository;

public class PostEditActivity extends AppCompatActivity {

    public static final String EXTRA_POST_ID = "post_id";

    private PostRepository repo;
    private long postId;

    private EditText etTitle, etBody;
    private ImageView ivPreview;

    private Uri selectedImageUri;
    private Uri cameraOutputUri;

    private ActivityResultLauncher<String[]> pickImageLauncher;
    private ActivityResultLauncher<Uri> takePictureLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_post_edit);

        repo = new PostRepository(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        etTitle = findViewById(R.id.etTitle);
        etBody = findViewById(R.id.etBody);
        ivPreview = findViewById(R.id.ivPreview);

        Button btnGallery = findViewById(R.id.btnGallery);
        Button btnCamera = findViewById(R.id.btnCamera);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnBack = findViewById(R.id.btnBack);

        // ---------- GALLERY ----------
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
                        ivPreview.setImageURI(uri);
                    }
                }
        );

        // ---------- CAMERA ----------
        takePictureLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success && cameraOutputUri != null) {
                        selectedImageUri = cameraOutputUri;
                        ivPreview.setImageURI(selectedImageUri);
                    }
                }
        );

        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) launchCamera();
                    else Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
                }
        );

        btnGallery.setOnClickListener(v ->
                pickImageLauncher.launch(new String[]{"image/*"})
        );

        btnCamera.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                launchCamera();
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });

        btnSave.setOnClickListener(v -> save());
        btnBack.setOnClickListener(v -> finish());

        postId = getIntent().getLongExtra(EXTRA_POST_ID, -1);
        if (postId <= 0) {
            Toast.makeText(this, "Invalid post", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        load();
    }

    private void load() {
        new Thread(() -> {
            try {
                Post p = repo.getById(postId).get();

                runOnUiThread(() -> {
                    if (p == null) {
                        finish();
                        return;
                    }

                    etTitle.setText(p.title == null ? "" : p.title);
                    etBody.setText(p.body == null ? "" : p.body);

                    if (p.imageUri != null && !p.imageUri.trim().isEmpty()) {
                        try {
                            selectedImageUri = Uri.parse(p.imageUri);
                            ivPreview.setImageURI(selectedImageUri);
                        } catch (SecurityException e) {
                            selectedImageUri = null;
                        }
                    }
                });

            } catch (ExecutionException | InterruptedException ignored) {}
        }).start();
    }

    private void save() {
        String title = etTitle.getText().toString().trim();
        String body = etBody.getText().toString().trim();

        if (body.isEmpty()) {
            Toast.makeText(this, "Body required", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                Post p = repo.getById(postId).get();
                if (p == null) return;

                p.title = title;
                p.body = body;
                p.imageUri = (selectedImageUri == null) ? null : selectedImageUri.toString();
                p.updatedAt = System.currentTimeMillis();

                repo.update(p).get();

                runOnUiThread(this::finish);

            } catch (Exception ignored) {}
        }).start();
    }

    private void launchCamera() {
        try {
            File dir = new File(getCacheDir(), "images");
            if (!dir.exists()) dir.mkdirs();

            File img = File.createTempFile("camera_", ".jpg", dir);

            cameraOutputUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    img
            );

            takePictureLauncher.launch(cameraOutputUri);

        } catch (IOException e) {
            Toast.makeText(this, "Camera error", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
