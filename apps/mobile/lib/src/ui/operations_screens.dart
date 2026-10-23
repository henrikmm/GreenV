/// The screens that read back what was uploaded, and the one that opens an order.
///
/// Every number on these screens came from the API in this run. The previous home screen said
/// "1.240 km", "12 alertas" and "291 trechos" from string literals, over a map painted by hand;
/// a field worker had no way to tell that from a measurement. Now a screen that cannot reach the
/// API says so and offers to try again, and capture — which lives elsewhere — never waits on it.
///
/// Each screen lives in its own file under `operations/`; this library only gathers them so
/// the navigation and the tests import one place.
library;

export 'package:greenv_capture/src/ui/operations/facts_table.dart';
export 'package:greenv_capture/src/ui/operations/formatting.dart';
export 'package:greenv_capture/src/ui/operations/home_screen.dart';
export 'package:greenv_capture/src/ui/operations/map_screen.dart';
export 'package:greenv_capture/src/ui/operations/order_form.dart';
export 'package:greenv_capture/src/ui/operations/orders_screen.dart';
export 'package:greenv_capture/src/ui/operations/remote_data.dart';
export 'package:greenv_capture/src/ui/operations/stretch_sheet.dart';
export 'package:greenv_capture/src/ui/operations/stretches_screen.dart';
export 'package:greenv_capture/src/ui/operations/teams_screen.dart';
