// Package corebundle exposes a small Android-friendly facade over both cores.
package corebundle

import (
	"errors"
	"os"
	"path/filepath"

	_ "github.com/2dust/AndroidLibXrayLite"
	_ "github.com/sagernet/gomobile/bind"
	libbox "github.com/sagernet/sing-box/experimental/libbox"
	_ "github.com/sagernet/sing-box/include"
)

type SingBoxController struct {
	server  *libbox.CommandServer
	running bool
}

func NewSingBoxController(basePath string, platformInterface libbox.PlatformInterface) (*SingBoxController, error) {
	if basePath == "" {
		return nil, errors.New("sing-box base path is empty")
	}
	workingPath := filepath.Join(basePath, "sing-box")
	if err := os.MkdirAll(workingPath, 0o700); err != nil {
		return nil, err
	}
	if err := libbox.Setup(&libbox.SetupOptions{
		BasePath: basePath, WorkingPath: workingPath, TempPath: filepath.Join(workingPath, "tmp"),
		FixAndroidStack: true, LogMaxLines: 2000,
	}); err != nil {
		return nil, err
	}
	c := &SingBoxController{}
	if platformInterface == nil {
		platformInterface = &platformStub{}
	}
	server, err := libbox.NewCommandServer(c, platformInterface)
	if err != nil {
		return nil, err
	}
	c.server = server
	return c, nil
}

func (c *SingBoxController) Start(config string) error {
	if c == nil || c.server == nil {
		return errors.New("sing-box controller is not initialized")
	}
	if err := c.server.StartOrReloadService(config, &libbox.OverrideOptions{}); err != nil {
		return err
	}
	c.running = true
	return nil
}

func (c *SingBoxController) Stop() error {
	if c == nil || c.server == nil {
		return nil
	}
	if err := c.server.CloseService(); err != nil {
		return err
	}
	c.running = false
	return nil
}

func (c *SingBoxController) Close() {
	if c != nil && c.server != nil {
		c.server.Close()
		c.server = nil
		c.running = false
	}
}

func (c *SingBoxController) IsRunning() bool { return c != nil && c.running }
func (c *SingBoxController) UplinkTotal() int64 {
	if c == nil || c.server == nil {
		return 0
	}
	instance := c.server.Instance()
	if instance == nil || instance.TrafficManager() == nil {
		return 0
	}
	uplink, _ := instance.TrafficManager().Total()
	return uplink
}
func (c *SingBoxController) DownlinkTotal() int64 {
	if c == nil || c.server == nil {
		return 0
	}
	instance := c.server.Instance()
	if instance == nil || instance.TrafficManager() == nil {
		return 0
	}
	_, downlink := instance.TrafficManager().Total()
	return downlink
}
func SingBoxVersion() string                 { return libbox.Version() }
func CheckSingBoxConfig(config string) error { return libbox.CheckConfig(config) }

func (c *SingBoxController) ServiceStop() error   { return c.Stop() }
func (c *SingBoxController) ServiceReload() error { return nil }
func (c *SingBoxController) GetSystemProxyStatus() (*libbox.SystemProxyStatus, error) {
	return nil, nil
}
func (c *SingBoxController) SetSystemProxyEnabled(bool) error { return nil }
func (c *SingBoxController) TriggerNativeCrash() error        { return nil }
func (c *SingBoxController) WriteDebugMessage(string)         {}
func (c *SingBoxController) ConnectSSHAgent() (int32, error)  { return -1, nil }

type platformStub struct{}

func (*platformStub) LocalDNSTransport() libbox.LocalDNSTransport { return nil }
func (*platformStub) UsePlatformAutoDetectInterfaceControl() bool { return false }
func (*platformStub) AutoDetectInterfaceControl(int32) error      { return nil }
func (*platformStub) OpenTun(libbox.TunOptions) (int32, error)    { return -1, os.ErrInvalid }
func (*platformStub) UseProcFS() bool                             { return false }
func (*platformStub) FindConnectionOwner(int32, string, int32, string, int32) (*libbox.ConnectionOwner, error) {
	return nil, os.ErrInvalid
}
func (*platformStub) StartDefaultInterfaceMonitor(libbox.InterfaceUpdateListener) error { return nil }
func (*platformStub) CloseDefaultInterfaceMonitor(libbox.InterfaceUpdateListener) error { return nil }
func (*platformStub) GetInterfaces() (libbox.NetworkInterfaceIterator, error) {
	return &emptyNetworkIterator{}, nil
}
func (*platformStub) UnderNetworkExtension() bool                              { return false }
func (*platformStub) IncludeAllNetworks() bool                                 { return false }
func (*platformStub) ReadWIFIState() *libbox.WIFIState                         { return nil }
func (*platformStub) ClearDNSCache()                                           {}
func (*platformStub) SendNotification(*libbox.Notification) error              { return nil }
func (*platformStub) CancelNotification(string, int32) error                   { return nil }
func (*platformStub) StartNeighborMonitor(libbox.NeighborUpdateListener) error { return nil }
func (*platformStub) CloseNeighborMonitor(libbox.NeighborUpdateListener) error { return nil }
func (*platformStub) RegisterMyInterface(string)                               {}
func (*platformStub) UsePlatformShell() bool                                   { return false }
func (*platformStub) CheckPlatformShell() error                                { return os.ErrInvalid }
func (*platformStub) OpenShellSession(*libbox.PlatformUser, string, libbox.StringIterator, string, int32, int32) (libbox.ShellSession, error) {
	return nil, os.ErrInvalid
}
func (*platformStub) LookupUser(string) (*libbox.PlatformUser, error) { return nil, os.ErrInvalid }
func (*platformStub) LookupSFTPServer() (string, error)               { return "", os.ErrInvalid }
func (*platformStub) ReadSystemSSHHostKey() (string, error)           { return "", os.ErrInvalid }
func (*platformStub) TailscaleHostname() string                       { return "" }
func (*platformStub) UsePlatformBridge() bool                         { return false }
func (*platformStub) CreateBridge(*libbox.BridgeOptions) (libbox.BridgeSession, error) {
	return nil, os.ErrInvalid
}

type emptyNetworkIterator struct{}

func (*emptyNetworkIterator) Next() *libbox.NetworkInterface { return nil }
func (*emptyNetworkIterator) HasNext() bool                  { return false }
