package com.freeapi.proxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.freeapi.proxy.ProxyRuntime
import com.freeapi.proxy.R
import com.freeapi.proxy.databinding.FragmentLogBinding

/**
 * 日志页。
 *
 * 用「版本号」而不是直接比对文本：日志每秒可能追加，逐次 toString + equals 在长日志下
 * 是纯粹的浪费。ProxyLog 每次变更自增 version，只有变了才重绘。
 */
class LogFragment : Fragment(), Tile {

    private var _b: FragmentLogBinding? = null
    private val b get() = _b!!

    private var renderedVersion = -1L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _b = FragmentLogBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.btnClearLog.setOnClickListener {
            ProxyRuntime.log.clear()
            renderedVersion = -1L
            refresh()
        }

        b.btnCopyLog.setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("freeapi-proxy-log", ProxyRuntime.log.asText()))
            Toast.makeText(requireContext(), "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        refresh()
    }

    override fun onDestroyView() {
        _b = null
        // view 没了，TextView 上的文本也一并消失，所以要允许下次重建时重绘
        renderedVersion = -1L
        super.onDestroyView()
    }

    override fun refresh() {
        val binding = _b ?: return
        val v = ProxyRuntime.log.version
        if (v == renderedVersion) return
        renderedVersion = v
        binding.tvLog.text = ProxyRuntime.log.asText().ifEmpty { "（暂无日志）" }
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
