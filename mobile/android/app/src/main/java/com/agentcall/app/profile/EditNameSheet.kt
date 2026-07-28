package com.agentcall.app.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentcall.app.ui.theme.Indigo400
import com.agentcall.app.ui.theme.Indigo600
import com.agentcall.app.ui.theme.Indigo800
import com.agentcall.app.ui.theme.Slate50
import com.agentcall.app.ui.theme.Slate900

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditNameSheet(
    currentName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(currentName) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Slate900,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                "Edit AI Name",
                style = MaterialTheme.typography.titleMedium,
                color = Slate50,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Indigo400,
                    unfocusedBorderColor = Indigo800,
                    cursorColor = Indigo400,
                    focusedTextColor = Slate50,
                    unfocusedTextColor = Slate50,
                ),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { if (name.isNotBlank()) onSave(name) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Indigo600),
                enabled = name.isNotBlank(),
            ) {
                Text("Save")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
