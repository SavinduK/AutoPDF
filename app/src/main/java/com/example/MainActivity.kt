package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.navigation.Routes
import com.example.ui.screens.ActiveCaptureScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PdfExportScreen
import com.example.ui.screens.RegionSelectorScreen
import com.example.ui.screens.ReviewGalleryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.SlideViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: SlideViewModel = viewModel()
                    val navController = rememberNavController()
                    val cropRegion by viewModel.currentCropRegion.collectAsState()

                    NavHost(
                        navController = navController,
                        startDestination = Routes.HOME
                    ) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                viewModel = viewModel,
                                onNavigateToRegionSelector = {
                                    navController.navigate(Routes.REGION_SELECTOR)
                                },
                                onNavigateToActiveCapture = {
                                    navController.navigate(Routes.ACTIVE_CAPTURE)
                                },
                                onNavigateToReview = { sessionId ->
                                    viewModel.selectSession(sessionId)
                                    navController.navigate(Routes.reviewGallery(sessionId))
                                },
                                onNavigateToPdfExport = { sessionId ->
                                    viewModel.selectSession(sessionId)
                                    navController.navigate(Routes.pdfExport(sessionId))
                                },
                                onNavigateToSettings = {
                                    navController.navigate(Routes.SETTINGS)
                                }
                            )
                        }

                        composable(Routes.REGION_SELECTOR) {
                            RegionSelectorScreen(
                                initialRegion = cropRegion,
                                onRegionSelected = { newRegion ->
                                    viewModel.setCropRegion(newRegion)
                                },
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable(Routes.ACTIVE_CAPTURE) {
                            ActiveCaptureScreen(
                                viewModel = viewModel,
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                                onNavigateToReview = { sessionId ->
                                    viewModel.selectSession(sessionId)
                                    navController.navigate(Routes.reviewGallery(sessionId)) {
                                        popUpTo(Routes.HOME)
                                    }
                                },
                                onNavigateToCropRegion = {
                                    navController.navigate(Routes.REGION_SELECTOR)
                                }
                            )
                        }

                        composable(
                            route = Routes.REVIEW_GALLERY,
                            arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
                        ) { backStackEntry ->
                            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: 0L
                            ReviewGalleryScreen(
                                sessionId = sessionId,
                                viewModel = viewModel,
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                                onNavigateToPdfExport = { sId ->
                                    viewModel.selectSession(sId)
                                    navController.navigate(Routes.pdfExport(sId))
                                }
                            )
                        }

                        composable(
                            route = Routes.PDF_EXPORT,
                            arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
                        ) { backStackEntry ->
                            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: 0L
                            PdfExportScreen(
                                sessionId = sessionId,
                                viewModel = viewModel,
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable(Routes.SETTINGS) {
                            SettingsScreen(
                                viewModel = viewModel,
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Retained for screenshot tests and preview verification
 */
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
