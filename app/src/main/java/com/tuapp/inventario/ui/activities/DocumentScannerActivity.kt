package com.tuapp.inventario.ui.activities

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tuapp.inventario.R
import com.tuapp.inventario.ui.views.CropOverlayView
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DocumentScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESULT = "scanner_result"
        private const val TAG = "DocumentScanner"
    }

    private lateinit var previewView: PreviewView
    private lateinit var cropOverlay: CropOverlayView
    private lateinit var capturedImage: ImageView
    private lateinit var cameraControls: LinearLayout
    private lateinit var cropControls: LinearLayout
    private lateinit var reviewControls: LinearLayout
    private lateinit var txtTitle: TextView
    private lateinit var txtHint: TextView
    private lateinit var txtPageCount: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnGallery: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var btnRotate: Button
    private lateinit var btnRetake: Button
    private lateinit var btnConfirm: Button
    private lateinit var btnAddPage: Button
    private lateinit var btnFinish: Button
    private lateinit var filterButtons: List<Button>

    private var cameraExecutor: ExecutorService? = null
    private var imageCapture: ImageCapture? = null
    private var currentBitmap: Bitmap? = null
    private var fullBitmap: Bitmap? = null
    private var currentFilter = "color"
    private val pages = mutableListOf<String>()
    private var isCropMode = false

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            contentResolver.openInputStream(it)?.use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                if (bmp != null) {
                    enterCropMode(bmp)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_document_scanner)

        previewView = findViewById(R.id.previewView)
        cropOverlay = findViewById(R.id.cropOverlay)
        capturedImage = findViewById(R.id.capturedImage)
        cameraControls = findViewById(R.id.cameraControls)
        cropControls = findViewById(R.id.cropControls)
        reviewControls = findViewById(R.id.reviewControls)
        txtTitle = findViewById(R.id.txtTitle)
        txtHint = findViewById(R.id.txtHint)
        txtPageCount = findViewById(R.id.txtPageCount)
        btnCapture = findViewById(R.id.btnCapture)
        btnGallery = findViewById(R.id.btnGallery)
        btnClose = findViewById(R.id.btnClose)
        btnRotate = findViewById(R.id.btnRotate)
        btnRetake = findViewById(R.id.btnRetake)
        btnConfirm = findViewById(R.id.btnConfirm)
        btnAddPage = findViewById(R.id.btnAddPage)
        btnFinish = findViewById(R.id.btnFinish)
        filterButtons = listOf(
            findViewById(R.id.btnFilterColor),
            findViewById(R.id.btnFilterEnhance),
            findViewById(R.id.btnFilterGray),
            findViewById(R.id.btnFilterBW)
        )

        btnClose.setOnClickListener { finish() }
        btnCapture.setOnClickListener { capturePhoto() }
        btnGallery.setOnClickListener { galleryLauncher.launch("image/*") }
        btnRotate.setOnClickListener { rotateImage() }
        btnRetake.setOnClickListener { retake() }
        btnConfirm.setOnClickListener { confirmScan() }
        btnAddPage.setOnClickListener { addPage() }
        btnFinish.setOnClickListener { finishScan() }

        filterButtons.forEach { btn ->
            btn.setOnClickListener {
                currentFilter = when (btn.id) {
                    R.id.btnFilterColor -> "color"
                    R.id.btnFilterEnhance -> "enhance"
                    R.id.btnFilterGray -> "gray"
                    R.id.btnFilterBW -> "bw"
                    else -> "color"
                }
                updateFilterButtons()
                applyFilterToPreview()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Se necesita permiso de cámara para escanear", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, preview, imageCapture)
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                Toast.makeText(this, "Error al iniciar cámara: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun capturePhoto() {
        val imageCapture = imageCapture ?: return
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bmp = imageProxyToBitmap(image)
                    image.close()
                    runOnUiThread {
                        if (bmp != null) {
                            enterCropMode(bmp)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture error", exception)
                    runOnUiThread {
                        Toast.makeText(this@DocumentScannerActivity, "Error al capturar", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            // Rotate based on image info
            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            }
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "imageProxyToBitmap error", e)
            null
        }
    }

    // === CROP MODE ===

    private fun enterCropMode(bmp: Bitmap) {
        isCropMode = true
        fullBitmap = bmp
        currentBitmap = bmp

        // Scale bitmap to fit screen
        val displayMetrics = resources.displayMetrics
        val maxW = displayMetrics.widthPixels
        val maxH = displayMetrics.heightPixels - 280
        val scale = min(maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height)
        val dispW = (bmp.width * scale).toInt()
        val dispH = (bmp.height * scale).toInt()

        val scaledBmp = Bitmap.createScaledBitmap(bmp, dispW, dispH, true)
        currentBitmap = scaledBmp

        capturedImage.setImageBitmap(scaledBmp)
        capturedImage.visibility = View.VISIBLE

        // Setup crop overlay
        val viewW = if (previewView.width > 0) previewView.width else resources.displayMetrics.widthPixels
        val viewH = if (previewView.height > 0) previewView.height else resources.displayMetrics.heightPixels
        val left = (viewW - dispW) / 2f
        val top = (viewH - dispH - 140) / 2f
        val imgRect = RectF(left, top, left + dispW, top + dispH)
        cropOverlay.imageRect = imgRect

        // Detect corners (simple bounding box with inset)
        val inset = 0.05f
        val ix = dispW * inset
        val iy = dispH * inset
        cropOverlay.corners = floatArrayOf(
            left + ix, top + iy,
            left + dispW - ix, top + iy,
            left + dispW - ix, top + dispH - iy,
            left + ix, top + dispH - iy
        )

        cropOverlay.visibility = View.VISIBLE
        cropOverlay.onCornersChanged = null

        // Switch UI
        cameraControls.visibility = View.GONE
        cropControls.visibility = View.VISIBLE
        reviewControls.visibility = View.GONE
        txtTitle.text = "Ajustar Recorte"
        txtHint.text = "Arrastra los puntos para ajustar el recorte"

        updateFilterButtons()
        applyFilterToPreview()
    }

    private fun rotateImage() {
        val bmp = fullBitmap ?: return
        val matrix = Matrix()
        matrix.postRotate(90f)
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        enterCropMode(rotated)
    }

    private fun retake() {
        isCropMode = false
        fullBitmap = null
        currentBitmap = null
        capturedImage.visibility = View.GONE
        cropOverlay.visibility = View.GONE
        cameraControls.visibility = View.VISIBLE
        cropControls.visibility = View.GONE
        reviewControls.visibility = View.GONE
        txtTitle.text = "Escanear Documento"
        txtHint.text = "Centra el documento en la cámara"
    }

    private fun confirmScan() {
        val bmp = fullBitmap ?: return
        val corners = cropOverlay.corners
        val imgRect = cropOverlay.imageRect ?: return

        // Convert display corners to full-res corners
        val scale = bmp.width.toFloat() / (imgRect.width())

        val srcCorners = FloatArray(8)
        for (i in 0 until 4) {
            val dispX = corners[i * 2] - imgRect.left
            val dispY = corners[i * 2 + 1] - imgRect.top
            srcCorners[i * 2] = dispX * scale
            srcCorners[i * 2 + 1] = dispY * scale
        }

        // Apply perspective correction
        val corrected = applyPerspectiveCorrection(bmp, srcCorners)

        // Apply filter
        val filtered = applyFilter(corrected, currentFilter)

        // Convert to base64
        val base64 = bitmapToBase64(filtered)

        // Add to pages
        pages.add(base64)

        // Show review
        showReview()
    }

    private fun showReview() {
        isCropMode = false
        capturedImage.visibility = View.GONE
        cropOverlay.visibility = View.GONE
        cropControls.visibility = View.GONE
        cameraControls.visibility = View.GONE
        reviewControls.visibility = View.VISIBLE
        txtTitle.text = "Revisar"
        txtPageCount.text = "${pages.size} página${if (pages.size > 1) "s" else ""} escaneada${if (pages.size > 1) "s" else ""}"
        txtHint.text = if (pages.size == 1) "Podés agregar el dorso o finalizar" else "Podés agregar más páginas o finalizar"
    }

    private fun addPage() {
        retake()
    }

    private fun finishScan() {
        val result = if (pages.size == 1) {
            pages[0]
        } else {
            createCollage(pages)
        }

        val intent = Intent()
        intent.putExtra(EXTRA_RESULT, result)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    // === PERSPECTIVE CORRECTION ===

    private fun applyPerspectiveCorrection(bmp: Bitmap, corners: FloatArray): Bitmap {
        val x0 = corners[0]; val y0 = corners[1]
        val x1 = corners[2]; val y1 = corners[3]
        val x2 = corners[4]; val y2 = corners[5]
        val x3 = corners[6]; val y3 = corners[7]

        // Calculate output size
        val w1 = distance(x0, y0, x1, y1)
        val w2 = distance(x2, y2, x3, y3)
        val h1 = distance(x0, y0, x3, y3)
        val h2 = distance(x1, y1, x2, y2)
        val outW = max(w1, w2).toInt().coerceAtMost(bmp.width)
        val outH = max(h1, h2).toInt().coerceAtMost(bmp.height)

        val src = floatArrayOf(x0, y0, x1, y1, x2, y2, x3, y3)
        val dst = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())

        val matrix = computePerspectiveTransform(src, dst)
        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        // Draw with perspective transform by mapping the quad to a rectangle
        // We use a mesh approach: draw triangles
        drawPerspectiveMesh(canvas, bmp, src, dst, paint)

        return output
    }

    private fun drawPerspectiveMesh(canvas: Canvas, bmp: Bitmap, src: FloatArray, dst: FloatArray, paint: Paint) {
        // Simple approach: use Matrix with approximate affine transform
        // For better quality, subdivide into triangles
        val subdivs = 20

        for (i in 0 until subdivs) {
            for (j in 0 until subdivs) {
                val u0 = i.toFloat() / subdivs
                val u1 = (i + 1).toFloat() / subdivs
                val v0 = j.toFloat() / subdivs
                val v1 = (j + 1).toFloat() / subdivs

                // Bilinear interpolate source
                val sx0 = bilinear(src, u0, v0, 0)
                val sy0 = bilinear(src, u0, v0, 1)
                val sx1 = bilinear(src, u1, v0, 0)
                val sy1 = bilinear(src, u1, v0, 1)
                val sx2 = bilinear(src, u1, v1, 0)
                val sy2 = bilinear(src, u1, v1, 1)
                val sx3 = bilinear(src, u0, v1, 0)
                val sy3 = bilinear(src, u0, v1, 1)

                // Destination is regular grid
                val dx0 = u0 * dst[4] // outW
                val dy0 = v0 * dst[5] // outH
                val dx1 = u1 * dst[4]
                val dy1 = v0 * dst[5]
                val dx2 = u1 * dst[4]
                val dy2 = v1 * dst[5]
                val dx3 = u0 * dst[4]
                val dy3 = v1 * dst[5]

                // Draw the quad as two triangles using clip + drawBitmap
                val path = Path().apply {
                    moveTo(dx0, dy0)
                    lineTo(dx1, dy1)
                    lineTo(dx2, dy2)
                    lineTo(dx3, dy3)
                    close()
                }

                // Approximate transform for this patch
                val cx = (sx0 + sx1 + sx2 + sx3) / 4f
                val cy = (sy0 + sy1 + sy2 + sy3) / 4f
                val dcx = (dx0 + dx1 + dx2 + dx3) / 4f
                val dcy = (dy0 + dy1 + dy2 + dy3) / 4f

                // Scale factor
                val srcW = (distance(sx0, sy0, sx1, sy1) + distance(sx2, sy2, sx3, sy3)) / 2f
                val dstW = (distance(dx0, dy0, dx1, dy1) + distance(dx2, dy2, dx3, dy3)) / 2f
                val srcH = (distance(sx0, sy0, sx3, sy3) + distance(sx1, sy1, sx2, sy2)) / 2f
                val dstH = (distance(dx0, dy0, dx3, dy3) + distance(dx1, dy1, dx2, dy2)) / 2f

                if (srcW < 1f || srcH < 1f || dstW < 1f || dstH < 1f) continue

                val scaleX = dstW / srcW
                val scaleY = dstH / srcH

                canvas.save()
                canvas.clipPath(path)
                val matrix = Matrix()
                matrix.postScale(scaleX, scaleY)
                matrix.postTranslate(dcx - cx * scaleX, dcy - cy * scaleY)
                canvas.drawBitmap(bmp, matrix, paint)
                canvas.restore()
            }
        }
    }

    private fun bilinear(corners: FloatArray, u: Float, v: Float, axis: Int): Float {
        val off = axis
        val a = corners[0 + off] // TL
        val b = corners[2 + off] // TR
        val c = corners[4 + off] // BR
        val d = corners[6 + off] // BL
        return (1 - u) * (1 - v) * a + u * (1 - v) * b + u * v * c + (1 - u) * v * d
    }

    private fun computePerspectiveTransform(src: FloatArray, dst: FloatArray): Matrix {
        // Use affine approximation (good enough for slight perspective)
        val matrix = Matrix()
        val srcPoints = floatArrayOf(src[0], src[1], src[2], src[3], src[4], src[5])
        val dstPoints = floatArrayOf(dst[0], dst[1], dst[2], dst[3], dst[4], dst[5])
        matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 3)
        return matrix
    }

    // === FILTERS ===

    private fun applyFilter(bmp: Bitmap, filter: String): Bitmap {
        return when (filter) {
            "color" -> bmp
            "enhance" -> enhanceImage(bmp)
            "gray" -> toGrayscale(bmp)
            "bw" -> toBlackAndWhite(bmp)
            else -> bmp
        }
    }

    private fun enhanceImage(bmp: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(1.2f)
            // Contrast
            val scale = 1.15f
            val translate = -0.5f * scale + 0.5f
            setScale(scale, scale, scale, 1f)
            postConcat(ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, translate * 255,
                0f, 1f, 0f, 0f, translate * 255,
                0f, 0f, 1f, 0f, translate * 255,
                0f, 0f, 0f, 1f, 0f
            )))
        })
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        return output
    }

    private fun toGrayscale(bmp: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(0f)
        })
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        return output
    }

    private fun toBlackAndWhite(bmp: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(0f)
            // Increase contrast for B&W
            val scale = 2f
            val translate = -0.5f * scale + 0.5f
            setScale(scale, scale, scale, 1f)
            postConcat(ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, translate * 255,
                0f, 1f, 0f, 0f, translate * 255,
                0f, 0f, 1f, 0f, translate * 255,
                0f, 0f, 0f, 1f, 0f
            )))
        })
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        return output
    }

    private fun applyFilterToPreview() {
        val bmp = currentBitmap ?: return
        val filtered = applyFilter(bmp, currentFilter)
        capturedImage.setImageBitmap(filtered)
    }

    private fun updateFilterButtons() {
        val activeId = when (currentFilter) {
            "color" -> R.id.btnFilterColor
            "enhance" -> R.id.btnFilterEnhance
            "gray" -> R.id.btnFilterGray
            "bw" -> R.id.btnFilterBW
            else -> R.id.btnFilterColor
        }
        filterButtons.forEach { btn ->
            if (btn.id == activeId) {
                btn.setBackgroundResource(R.drawable.scanner_filter_active)
                btn.setTextColor(ContextCompat.getColor(this, R.color.brand_black))
            } else {
                btn.setBackgroundResource(android.R.color.transparent)
                btn.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
        }
    }

    // === COLLAGE ===

    private fun createCollage(pageBase64List: List<String>): String {
        val bitmaps = pageBase64List.mapNotNull { b64 ->
            try {
                val data = if (b64.startsWith("data:image")) b64.substringAfter(",") else b64
                val bytes = Base64.decode(data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        }

        if (bitmaps.isEmpty()) return ""
        if (bitmaps.size == 1) return pageBase64List[0]

        val targetWidth = 1000
        val scaledBitmaps = bitmaps.map { bmp ->
            val scale = targetWidth.toFloat() / bmp.width.toFloat()
            val targetHeight = (bmp.height * scale).toInt()
            Bitmap.createScaledBitmap(bmp, targetWidth, targetHeight, true)
        }

        val headerMargin = 40
        val dividerSpace = 15
        var totalHeight = 0
        scaledBitmaps.forEach { totalHeight += it.height + headerMargin + dividerSpace }

        val combined = Bitmap.createBitmap(targetWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        canvas.drawColor(Color.parseColor("#0F172A"))

        val paintText = Paint().apply {
            color = Color.parseColor("#FBBF24")
            textSize = 28f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val paintLine = Paint().apply {
            color = Color.parseColor("#E6C874")
            strokeWidth = 4f
            isAntiAlias = true
        }

        var currentY = 0f
        scaledBitmaps.forEachIndexed { index, bmp ->
            val tag = if (index == 0) "FRENTE" else if (index == 1) "DORSO / REVERSO" else "PARTE ${index + 1}"
            canvas.drawText(tag, 20f, currentY + 30f, paintText)
            currentY += headerMargin
            canvas.drawBitmap(bmp, 0f, currentY, null)
            currentY += bmp.height
            canvas.drawLine(0f, currentY + 5f, targetWidth.toFloat(), currentY + 5f, paintLine)
            currentY += dividerSpace
        }

        return bitmapToBase64(combined)
    }

    // === UTILS ===

    private fun bitmapToBase64(bmp: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return "data:image/jpeg;base64," + Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return kotlin.math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor?.shutdown()
    }
}
