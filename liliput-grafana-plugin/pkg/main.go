package main

import (
	"os"

	"github.com/grafana/grafana-plugin-sdk-go/backend"
	"github.com/grafana/grafana-plugin-sdk-go/backend/datasource"

	"github.com/liliput/liliput-datasource/pkg/plugin"
)

func main() {
	err := datasource.Manage(
		"liliput-datasource",
		plugin.NewDatasource,
		datasource.ManageOpts{},
	)
	if err != nil {
		backend.Logger.Error(err.Error())
		os.Exit(1)
	}
}
